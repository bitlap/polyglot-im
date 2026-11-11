import '../common/types.dart';
import '../domain/message.dart';

abstract interface class NotificationPublisher {
  Future<ImMessage> send({
    required String recipientId,
    required String clientMessageId,
    required String type,
    required JsonMap payload,
  });
}
