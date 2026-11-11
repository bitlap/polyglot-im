import '../ports/remote_api.dart';
import '../ports/notification_publisher.dart';
import '../common/types.dart';
import '../domain/message.dart';

/// 以服务身份发布业务通知；同一业务事件的重试必须复用幂等键。
class NotificationSender implements NotificationPublisher {
  /// 借用已配置服务身份的 API 入口，后续发送不创建独立连接池。
  const NotificationSender(this.api);
  final RemoteApi api;

  /// 使用稳定业务事件键发布通知并返回已提交消息；重试不生成新的发送意图。
  @override
  Future<ImMessage> send({
    required String recipientId,
    required String clientMessageId,
    required String type,
    required JsonMap payload,
  }) async {
    if (clientMessageId.isEmpty) {
      throw ArgumentError('stable clientMessageId required');
    }
    return ImMessage.fromJson(
      await api.request(
        'POST',
        '/notifications',
        body: {
          'recipient_id': recipientId,
          'client_message_id': clientMessageId,
          'type': type,
          'payload': payload,
        },
      ),
    );
  }
}
