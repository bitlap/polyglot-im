import '../ports/remote_api.dart';
import '../ports/notification_publisher.dart';
import '../common/types.dart';
import '../domain/message.dart';

class NotificationSender implements NotificationPublisher {
  const NotificationSender(this.api);
  final RemoteApi api;
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
