/// 已建立的实时会话，提供入站帧和确认发送；确认前必须完成本地提交。
abstract interface class SocketSession {
  /// 等待当前 WebSocket 连接完成握手。
  Future<void> get ready;

  /// 读取当前会话的入站消息；流结束或失败表示需要由同步层恢复连接。
  Stream<Object?> get messages;

  /// 向当前实时连接写入编码帧；连接或写入失败交给调用方处理。
  void send(Object value);

  /// 关闭当前实时会话并释放连接，不注销服务端设备。
  Future<void> close();
}

/// 创建实时会话的端口；调用方负责结束会话和释放连接。
abstract interface class SocketConnector {
  /// 建立目标实时会话；连接失败交给调用方处理，退出时释放底层连接。
  SocketSession connect(Uri uri);
}
