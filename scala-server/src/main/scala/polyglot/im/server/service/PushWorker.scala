package polyglot.im.server.service
import cats.effect.Async
import cats.effect.syntax.all.*
import cats.syntax.all.*
import fs2.Stream
import scala.concurrent.duration.*
import polyglot.im.server.config.PushWorkerSettings
import polyglot.im.server.domain.{PushJob, PushResult}
import polyglot.im.server.ports.{PushProvider, PushRepository}
object PushWorker {
  val defaults = PushWorkerSettings(8, 60.seconds, 20.seconds, 1.second)
  def run[F[_]: Async](
      store: PushRepository[F],
      provider: PushProvider[F],
      settings: PushWorkerSettings = defaults
  ): F[Unit] =
    Stream
      .awakeEvery[F](settings.pollInterval)
      .evalMap(_ =>
        store.claimBatch(settings.parallelism, settings.leaseDuration.toSeconds.toInt).handleErrorWith { _ =>
          Async[F]
            .delay(org.slf4j.LoggerFactory.getLogger("polyglot.im.push").error("Outbox claim failed; retrying"))
            .as(List.empty[PushJob])
        }
      )
      .flatMap { jobs =>
        Stream.emits(jobs).covary[F].parEvalMapUnordered(settings.parallelism) { job =>
          store
            .pushTarget(job)
            .flatMap {
              case None => store.finish(job, PushResult(false), None)
              case Some(target) =>
                provider
                  .send(target)
                  .handleError(_ => PushResult(true, false, "provider failure"))
                  .flatMap(result => store.finish(job, result, Some(target.token)))
            }
            .timeout(settings.jobTimeout)
            .handleErrorWith { _ =>
              Async[F].delay(
                org.slf4j.LoggerFactory.getLogger("polyglot.im.push").error("Outbox job failed; lease will expire")
              )
            }
        }
      }
      .compile
      .drain
}
