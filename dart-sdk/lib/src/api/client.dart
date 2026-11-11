import 'package:web_socket_channel/web_socket_channel.dart';
import '../service/sync_client.dart';
import '../ports/socket_connector.dart';
import '../adapters/codec/json_codec.dart';
import '../adapters/transport/web_socket_connector.dart';

class ImClient extends SyncClient {
  ImClient({
    required super.api,
    required super.scope,
    required super.deviceId,
    required super.store,
    super.ownsApi,
    WebSocketChannel Function(Uri)? socketFactory,
    SocketConnector? socketConnector,
    super.codec = const JsonWireCodec(),
  }) : super(socketConnector: _choose(socketConnector, socketFactory));
  static SocketConnector _choose(
    SocketConnector? connector,
    WebSocketChannel Function(Uri)? factory,
  ) {
    if (connector != null && factory != null) {
      throw ArgumentError('choose socketConnector or socketFactory');
    }
    return connector ?? WebSocketConnector(factory);
  }
}
