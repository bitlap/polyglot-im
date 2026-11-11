class ImException implements Exception {
  const ImException(this.status, this.code, this.message);
  final int status;
  final String code;
  final String message;
  @override
  String toString() => 'ImException($status, $code): $message';
}

class TransportException implements Exception {
  const TransportException(this.message);
  final String message;
  @override
  String toString() => 'TransportException: $message';
}
