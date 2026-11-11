package polyglot.im.ports
import io.circe.Json
import polyglot.im.domain.AccountScope

/** 按账号作用域保存消息和同步水位；合并与游标更新必须原子提交。 */
trait LocalStore[F[_]] {

  /** 读取指定账号作用域的缓存；尚无记录时返回空状态。 */
  def load(scope: AccountScope): F[Json]

  /** 原子合并消息和游标并返回提交后的状态；reset 表示用快照重建缓存。 */
  def commit(scope: AccountScope, page: Json, reset: Boolean = false): F[Json]

  /** 只清除指定账号作用域的本地缓存，不删除服务器消息。 */
  def clear(scope: AccountScope): F[Unit]
}
