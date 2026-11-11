abstract interface class SocketSession {
  Future<void> get ready;
  Stream<Object?> get messages;
  void send(Object value);
  Future<void> close();
}

abstract interface class SocketConnector {
  SocketSession connect(Uri uri);
}
