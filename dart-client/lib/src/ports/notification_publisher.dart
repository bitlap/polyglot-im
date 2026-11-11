import '../common/types.dart';
import '../domain/message.dart';

/// 业务通知发布端口；接受成功表示 IM 已提交，不表示用户已读。
abstract interface class NotificationPublisher {
  /// 使用稳定业务事件键发布通知并返回已提交消息；重试不生成新的发送意图。
  Future<ImMessage> send({
    required String recipientId,
    required String clientMessageId,
    required String type,
    required JsonMap payload,
  });
}
