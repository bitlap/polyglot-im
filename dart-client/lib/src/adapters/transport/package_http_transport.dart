import 'package:http/http.dart' as http;
import '../../ports/http_transport.dart';
import '../../common/errors.dart';

/// 包装 package:http 客户端；仅当 ownsClient 为真时负责关闭它。
class PackageHttpTransport implements HttpTransport {
  /// 包装传入的 http.Client；ownsClient 决定 close 是否关闭底层客户端。
  PackageHttpTransport(this.client, {this.ownsClient = false});
  final http.Client client;
  final bool ownsClient;

  /// 执行一次传输并保留真实 HTTP 状态；不在此层推断消息送达或已读。
  @override
  Future<HttpResponse> execute(HttpRequest value) async {
    try {
      final request = http.Request(value.method, value.uri);
      request.headers.addAll(value.headers);
      if (value.body != null) request.bodyBytes = value.body!;
      final response = await http.Response.fromStream(
        await client.send(request),
      );
      return HttpResponse(
        response.statusCode,
        response.bodyBytes,
        headers: response.headers,
      );
    } on http.ClientException catch (error) {
      throw TransportException(error.message);
    }
  }

  /// 仅当 ownsClient 为真时关闭底层 HTTP 客户端。
  @override
  void close() {
    if (ownsClient) client.close();
  }
}
