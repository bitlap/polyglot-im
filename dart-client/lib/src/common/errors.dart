/// IM 业务请求异常，区分 HTTP 状态、稳定错误码和错误说明。
class ImException implements Exception {
  /// 保存 HTTP 状态、业务错误码与错误说明，供调用方分类处理。
  const ImException(this.status, this.code, this.message);
  final int status;
  final String code;
  final String message;

  /// 返回供诊断使用的文本表示；配置类型的实现会隐藏敏感字段。
  @override
  String toString() => 'ImException($status, $code): $message';
}

/// 无法完成网络传输时抛出的异常，供业务层决定是否重试。
class TransportException implements Exception {
  /// 保存本次传输失败的说明，不伪造 HTTP 或业务状态。
  const TransportException(this.message);
  final String message;

  /// 返回供诊断使用的文本表示；配置类型的实现会隐藏敏感字段。
  @override
  String toString() => 'TransportException: $message';
}
