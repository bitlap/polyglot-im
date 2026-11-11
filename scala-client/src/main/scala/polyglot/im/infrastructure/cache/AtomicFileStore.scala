package polyglot.im.infrastructure.cache

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.channels.FileChannel
import java.nio.file.{Files, Path, StandardOpenOption, StandardCopyOption}
import java.util.UUID
import cats.effect.Async
import io.circe.Json
import io.circe.parser.parse
import polyglot.im.domain.{AccountScope, LocalCache}
import polyglot.im.ports.LocalStore

/** JVM 持久缓存适配器：跨进程文件锁 + 原子替换，失败不会确认游标。 */
final class AtomicFileStore[F[_]: Async](directory: Path) extends LocalStore[F] {
  private def transaction[A](scope: AccountScope)(run: Path => A): F[A] = Async[F].blocking {
    Files.createDirectories(directory)
    val digest = java.security.MessageDigest
      .getInstance("SHA-256")
      .digest(scope.key.getBytes(UTF_8))
      .map(b => f"${b & 255}%02x")
      .mkString
    val channel =
      FileChannel.open(directory.resolve(digest + ".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
    try {
      var lock: java.nio.channels.FileLock = null
      while (lock == null)
        try lock = channel.lock()
        catch { case _: java.nio.channels.OverlappingFileLockException => Thread.sleep(10) }
      try run(directory.resolve(digest + ".json"))
      finally lock.release()
    } finally channel.close()
  }
  private def read(path: Path): Json = if (Files.exists(path))
    parse(Files.readString(path)).toOption.getOrElse(throw new IllegalStateException("corrupt cache"))
  else LocalCache.empty

  /** 读取指定账号作用域的缓存；尚无记录时返回空状态。 */
  def load(scope: AccountScope): F[Json] = transaction(scope)(read)

  /** 原子合并消息和游标并返回提交后的状态；reset 表示用快照重建缓存。 */
  def commit(scope: AccountScope, page: Json, reset: Boolean = false): F[Json] = transaction(scope) { path =>
    val result = LocalCache.applyPage(read(path), page, reset)
    val temp   = path.resolveSibling(path.getFileName.toString + "." + UUID.randomUUID() + ".tmp")
    try {
      val file = FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
      try {
        val bytes = java.nio.ByteBuffer.wrap(result.noSpaces.getBytes(UTF_8));
        while (bytes.hasRemaining) file.write(bytes); file.force(true)
      } finally file.close()
      Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
      result
    } finally Files.deleteIfExists(temp)
  }

  /** 只清除指定账号作用域的本地缓存，不删除服务器消息。 */
  def clear(scope: AccountScope): F[Unit] = transaction(scope) { path =>
    Files.deleteIfExists(path); ()
  }
}
