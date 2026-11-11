/// 协议 v1 消息；未知消息类型及 payload 由宿主应用决定展示方式。
final class ImMessage {
  /// 校验 v1 消息身份、正整数序号和 UTC 时间，并将 payload 复制为只读映射。
  ImMessage({
    required this.schemaVersion,
    required this.messageId,
    required this.conversationId,
    required this.conversationSeq,
    required this.type,
    required Map<String, dynamic> payload,
    required this.createdAt,
  }) : payload = Map.unmodifiable(payload) {
    if (schemaVersion != 1 ||
        messageId.isEmpty ||
        conversationId.isEmpty ||
        type.isEmpty ||
        !RegExp(r'^[1-9][0-9]*$').hasMatch(conversationSeq)) {
      throw const FormatException('Invalid message envelope');
    }
    if (!createdAt.endsWith('Z') || DateTime.tryParse(createdAt) == null) {
      throw const FormatException('created_at must be UTC');
    }
  }

  final int schemaVersion;
  final String messageId;
  final String conversationId;
  final String conversationSeq;
  final String type;
  final Map<String, dynamic> payload;
  final String createdAt;

  /// 从 snake_case 协议对象创建消息，并执行消息模型校验。
  factory ImMessage.fromJson(Map<String, dynamic> json) => ImMessage(
    schemaVersion: json['schema_version'] as int,
    messageId: json['message_id'] as String,
    conversationId: json['conversation_id'] as String,
    conversationSeq: json['conversation_seq'] as String,
    type: json['type'] as String,
    payload: json['payload'] as Map<String, dynamic>,
    createdAt: json['created_at'] as String,
  );

  /// 导出 snake_case 消息信封，不改写序号、类型或载荷。
  Map<String, dynamic> toJson() => {
    'schema_version': schemaVersion,
    'message_id': messageId,
    'conversation_id': conversationId,
    'conversation_seq': conversationSeq,
    'type': type,
    'payload': payload,
    'created_at': createdAt,
  };
}
