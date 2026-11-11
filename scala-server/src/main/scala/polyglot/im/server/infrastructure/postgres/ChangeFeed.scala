package polyglot.im.server.infrastructure.postgres

import polyglot.im.server.config.DatabaseConfig
import polyglot.im.server.domain.Identity
import polyglot.im.server.domain.Protocol
import polyglot.im.server.ports.ChangeNotifications

import java.sql.{Connection, DriverManager}
import scala.concurrent.duration.*
import cats.effect.{Async, Ref, Resource}
import cats.effect.std.Queue
import cats.effect.syntax.all.*
import cats.syntax.all.*
import fs2.Stream
import org.postgresql.PGConnection

/** 通知仅作为唤醒提示；事件与游标仍从 PostgreSQL 持久流读取。 */
final class ChangeFeed[F[_]: Async] private (
    subscribers: Ref[F, Map[String, Map[String, Queue[F, Unit]]]],
    connect: F[Connection],
    status: Ref[F, Boolean]
) extends ChangeNotifications[F] {
  def connected: F[Boolean] = status.get
  def subscribe(who: Identity): Resource[F, Stream[F, Unit]] = for {
    queue <- Resource.eval(Queue.dropping[F, Unit](1))
    id  = Protocol.id
    key = ChangeFeed.key(who)
    _ <- Resource.make(subscribers.update { all =>
      all.updated(key, all.getOrElse(key, Map.empty).updated(id, queue))
    })(_ =>
      subscribers.update { all =>
        val remaining = all.getOrElse(key, Map.empty) - id
        if (remaining.isEmpty) all - key else all.updated(key, remaining)
      }
    )
  } yield Stream.emit(()) ++ Stream.repeatEval(queue.take).merge(Stream.awakeEvery[F](5.seconds).void)

  private def wake(key: String): F[Unit] =
    subscribers.get.flatMap(_.getOrElse(key, Map.empty).values.toList.traverse_(_.offer(())))
  private def wakeAll: F[Unit] = subscribers.get.flatMap(_.values.toList.flatMap(_.values).traverse_(_.offer(())))

  private[server] def listen: F[Unit] = Async[F].defer {
    Resource
      .make(connect)(c => Async[F].blocking(c.close()).handleError(_ => ()))
      .use { connection =>
        for {
          _ <- Async[F].blocking {
            connection.setAutoCommit(true)
            val statement = connection.createStatement()
            try statement.execute("LISTEN polyglot_im_changes")
            finally statement.close()
          }
          // LISTEN 提交后补拉一次，覆盖启动和断线重连窗口。
          _ <- wakeAll
          _ <- status.set(true)
          pg = connection.unwrap(classOf[PGConnection])
          _ <- Stream
            .repeatEval(Async[F].blocking(Option(pg.getNotifications(1000)).toList.flatten))
            .flatMap(values => Stream.emits(values))
            .evalMap(notification => wake(notification.getParameter))
            .compile
            .drain
        } yield ()
      }
      .guarantee(status.set(false))
      .handleErrorWith { _ =>
        Async[F].delay(
          org.slf4j.LoggerFactory
            .getLogger("polyglot.im.changes")
            .warn("Change listener disconnected; durable reconciliation remains active")
        ) *>
          Async[F].sleep(1.second) *> listen
      }
  }
}
object ChangeFeed {
  def key(who: Identity): String = Protocol.conversation(who.app, who.user)
  def resource[F[_]: Async](connect: F[Connection]): Resource[F, ChangeFeed[F]] = for {
    subscribers <- Resource.eval(Ref.of[F, Map[String, Map[String, Queue[F, Unit]]]](Map.empty))
    status      <- Resource.eval(Ref.of[F, Boolean](false))
    feed = new ChangeFeed(subscribers, connect, status)
    _ <- Resource.make(feed.listen.start)(_.cancel)
  } yield feed

  /** 独立直连，不占业务连接池；不能经过 transaction pooling 的代理。 */
  def postgres[F[_]: Async](config: DatabaseConfig): Resource[F, ChangeFeed[F]] =
    resource(Async[F].blocking {
      val properties = new java.util.Properties()
      properties.setProperty("user", config.user)
      properties.setProperty("password", config.password)
      properties.setProperty("connectTimeout", "10")
      properties.setProperty("socketTimeout", "30")
      properties.setProperty("tcpKeepAlive", "true")
      DriverManager.getConnection(config.url, properties)
    })
}
