import 'package:http/http.dart' as http;
import '../../ports/http_transport.dart';
import '../../common/errors.dart';

class PackageHttpTransport implements HttpTransport {
  PackageHttpTransport(this.client, {this.ownsClient = false});
  final http.Client client;
  final bool ownsClient;
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

  @override
  void close() {
    if (ownsClient) client.close();
  }
}
