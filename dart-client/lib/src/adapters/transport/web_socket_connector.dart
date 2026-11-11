import 'package:web_socket_channel/web_socket_channel.dart';
import '../../ports/socket_connector.dart';

/// 使用 WebSocket 库创建实时会话，默认适配器在实际连接时打开网络。
class WebSocketConnector implements SocketConnector {
  /// 保存通道工厂；省略工厂时使用默认 WebSocketChannel 实现。
  WebSocketConnector([WebSocketChannel Function(Uri)? factory])
    : _factory = factory ?? WebSocketChannel.connect;
  final WebSocketChannel Function(Uri) _factory;

  /// 建立目标实时会话；连接失败交给调用方处理，退出时释放底层连接。
  @override
  SocketSession connect(Uri uri) => WebSocketSession(_factory(uri));
}

/// 包装 WebSocket 通道，暴露就绪状态、入站帧和关闭操作。
class WebSocketSession implements SocketSession {
  /// 借用已创建的 WebSocket 通道，向同步层提供就绪与入站帧。
  WebSocketSession(this.channel);
  final WebSocketChannel channel;

  /// 等待当前 WebSocket 连接完成握手。
  @override
  Future<void> get ready => channel.ready;

  /// 读取当前会话的入站消息；流结束或失败表示需要由同步层恢复连接。
  @override
  Stream<Object?> get messages => channel.stream;

  /// 使用稳定业务事件键发布通知并返回已提交消息；重试不生成新的发送意图。
  @override
  void send(Object value) => channel.sink.add(value);

  /// 关闭当前 WebSocket 通道的发送端，结束会话。
  @override
  Future<void> close() async {
    await channel.sink.close();
  }
}
