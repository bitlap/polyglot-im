import 'package:polyglot_im_dart_sdk/polyglot_im_dart_sdk.dart';

/// 资源由宿主创建与关闭；Flutter 可传入 SQLite，测试可传入隔离的替身。
ImClient createClient({
  required String baseUrl,
  required AccountScope scope,
  required String deviceId,
  required Future<String> Function() tokenProvider,
  required HttpTransport transport,
  required SocketConnector socketConnector,
  required LocalStore store,
  WireCodec codec = const JsonWireCodec(),
}) => ImClient(
  api: HttpApi(
    baseUrl: baseUrl,
    tokenProvider: tokenProvider,
    transport: transport,
    codec: codec,
  ),
  scope: scope,
  deviceId: deviceId,
  store: store,
  socketConnector: socketConnector,
  codec: codec,
);
