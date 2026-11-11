/// 已解析目标 URI 的 HTTP 请求；目标来源由 HttpApi 在构造请求前校验。
class HttpRequest {
  /// 保存目标 URI、请求头及已编码的正文 bytes，不在传输层重复构造协议对象。
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

/// 传输层 HTTP 响应，保留真实状态码、响应正文和响应头。
class HttpResponse {
  /// 保存传输返回的状态、正文与响应头，交由 API 层判断业务结果。
  const HttpResponse(this.statusCode, this.body, {this.headers = const {}});
  final int statusCode;
  final List<int> body;
  final Map<String, String> headers;
}

/// 执行单次 HTTP 传输的端口；认证、业务重试与响应解释由上层负责。
abstract interface class HttpTransport {
  /// 执行一次传输并保留真实 HTTP 状态；不在此层推断消息送达或已读。
  Future<HttpResponse> execute(HttpRequest request);

  /// 释放传输实现拥有的连接资源，借用资源仍由宿主关闭。
  void close();
}
