package polyglot.im.server.service
import cats.effect.Async
import cats.effect.syntax.all.*
import cats.syntax.all.*
import fs2.Stream
import scala.concurrent.duration.*
import polyglot.im.server.config.PushWorkerSettings
import polyglot.im.server.domain.{PushJob, PushResult}
import polyglot.im.server.ports.{PushProvider, PushRepository}

/** 按租约领取推送任务并有界并发执行；失败通过仓储记录重试结果。 */
object PushWorker {
  val defaults = PushWorkerSettings(8, 60.seconds, 20.seconds, 1.second)

  /** 按配置持续领取并有界并发投递推送任务；取消时停止领取并保留可恢复租约。 */
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
            .delay(org.slf4j.LoggerFactory.getLogger("polyglot.im.push").error("推送任务领取失败，等待下轮重试"))
            .as(List.empty[PushJob])
        }
      )
      .flatMap { jobs =>
        Stream.emits(jobs).covary[F].parEvalMapUnordered(settings.parallelism) { job =>
          store
            .pushTarget(job)
            .flatMap {
              case None => store.finish(job, PushResult(false, reason = "skipped: no eligible push target"), None)
              case Some(target) =>
                provider
                  .send(target)
                  .handleError(_ => PushResult(true, false, "provider failure"))
                  .flatMap { result =>
                    store.finish(job, result, Some(target.token)) *> Async[F].delay {
                      val logger = org.slf4j.LoggerFactory.getLogger("polyglot.im.push")
                      // 任务关联日志不输出用户、设备、令牌、负载或自定义 Provider 的错误正文。
                      if (result.retry || result.rejected || result.invalidToken)
                        logger.warn(
                          s"推送任务失败 job_id=${job.id} message_id=${job.message} attempt=${job.attempts} retry=${result.retry} invalid_token=${result.invalidToken}"
                        )
                    }
                  }
            }
            .timeout(settings.jobTimeout)
            .handleErrorWith { _ =>
              Async[F].delay(
                org.slf4j.LoggerFactory
                  .getLogger("polyglot.im.push")
                  .error(s"推送任务执行失败，等待租约过期 job_id=${job.id} message_id=${job.message} attempt=${job.attempts}")
              )
            }
        }
      }
      .compile
      .drain
}
