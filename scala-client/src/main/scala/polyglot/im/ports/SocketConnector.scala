package polyglot.im.ports
import fs2.Stream
import java.net.URI

/** 已建立的实时会话，提供入站帧和确认发送；确认前必须完成本地提交。 */
trait SocketSession[F[_]] {

  /** 读取当前会话的入站消息；流结束或失败表示需要由同步层恢复连接。 */
  def messages: Stream[F, String]

  /** 确认已完成本地提交的接收位置，不推进用户阅读水位。 */
  def ack(cursor: String): F[Unit]
}

/** 创建实时会话的端口；调用方负责结束会话和释放连接。 */
trait SocketConnector[F[_]] {

  /** 建立目标实时会话；连接失败交给调用方处理，退出时释放底层连接。 */
  def connect(uri: URI): cats.effect.Resource[F, SocketSession[F]]
}
