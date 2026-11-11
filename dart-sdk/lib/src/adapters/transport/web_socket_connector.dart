import 'package:web_socket_channel/web_socket_channel.dart';
import '../../ports/socket_connector.dart';

class WebSocketConnector implements SocketConnector {
  WebSocketConnector([WebSocketChannel Function(Uri)? factory])
    : _factory = factory ?? WebSocketChannel.connect;
  final WebSocketChannel Function(Uri) _factory;
  @override
  SocketSession connect(Uri uri) => WebSocketSession(_factory(uri));
}

class WebSocketSession implements SocketSession {
  WebSocketSession(this.channel);
  final WebSocketChannel channel;
  @override
  Future<void> get ready => channel.ready;
  @override
  Stream<Object?> get messages => channel.stream;
  @override
  void send(Object value) => channel.sink.add(value);
  @override
  Future<void> close() async {
    await channel.sink.close();
  }
}
