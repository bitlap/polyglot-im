class HttpRequest {
  const HttpRequest(
    this.method,
    this.uri, {
    this.headers = const {},
    this.body,
  });
  final String method;
  final Uri uri;
  final Map<String, String> headers;
  final List<int>? body;
}

class HttpResponse {
  const HttpResponse(this.statusCode, this.body, {this.headers = const {}});
  final int statusCode;
  final List<int> body;
  final Map<String, String> headers;
}

abstract interface class HttpTransport {
  Future<HttpResponse> execute(HttpRequest request);
  void close();
}
