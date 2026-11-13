import 'dart:convert';
import '../common/types.dart';
import 'message.dart';

/// 返回无消息、无游标且各计数为零的初始缓存。
JsonMap emptyCache() => {
  'messages': <String, dynamic>{},
  'cursor': null,
  'event_position': '0',
  'latest_seq': '0',
  'read_seq': '0',
  'unread_count': '0',
  'chat_unread_count': '0',
};

/// 原子提交前合并同步页；通知按单条标志或账号水位判已读，历史补页不复活未读，版本不变。
JsonMap applyPage(JsonMap current, JsonMap page, {bool reset = false}) {
  if (BigInt.parse(
        '${page['history_position'] ?? page['event_position'] ?? current['event_position']}',
      ) <
      BigInt.parse('${current['event_position']}')) {
    return current;
  }
  final result = reset
      ? emptyCache()
      : jsonDecode(jsonEncode(current)) as JsonMap;
  final messages = result['messages'] as JsonMap;

  /// 将一条已校验消息按 message_id 合并到本次缓存结果。
  void add(dynamic value) {
    final message = ImMessage.fromJson(Map<String, dynamic>.from(value as Map));
    final previous = messages[message.messageId] as Map?;
    if (BigInt.parse('${previous?['state_version'] ?? '0'}') <=
        BigInt.parse(message.stateVersion)) {
      messages[message.messageId] = message.toJson();
    }
  }

  for (final value in (page['messages'] as List? ?? const [])) {
    add(value);
  }
  for (final event in (page['events'] as List? ?? const [])) {
    if (event['type'] == 'message.created' ||
        event['type'] == 'notification.updated') {
      add(event['data']);
    }
  }
  final conversations =
      result.putIfAbsent('conversations', () => <String, dynamic>{}) as JsonMap;
  final patches = <dynamic>[
    ...page['conversations'] as List? ?? const [],
    for (final event in page['events'] as List? ?? const [])
      if (event['type'] == 'conversation.updated') event['data'],
  ];
  for (final patch in patches) {
    final cid = patch['conversation_id'] as String;
    final previous =
        conversations[cid] as JsonMap? ??
        {'revision': '0', 'messages': <String, dynamic>{}};
    final saved = Map<String, dynamic>.from(previous['messages'] as Map);
    for (final value in patch['messages'] as List? ?? const []) {
      final message = ImMessage.fromJson(
        Map<String, dynamic>.from(value as Map),
      );
      if (message.conversationId != cid) {
        throw const FormatException('message conversation mismatch');
      }
      saved[message.messageId] = message.toJson();
    }
    final metadata =
        BigInt.parse('${previous['revision']}') >
            BigInt.parse('${patch['revision']}')
        ? previous
        : Map<String, dynamic>.from(patch as Map);
    conversations[cid] = {...metadata, 'messages': saved};
  }
  for (final name in [
    'cursor',
    'event_position',
    'latest_seq',
    'read_seq',
    'unread_count',
    'chat_unread_count',
    'conversation_id',
  ]) {
    if (page.containsKey(name)) result[name] = page[name];
  }
  for (final key in ['latest_seq', 'read_seq']) {
    if (!reset &&
        BigInt.parse('${current[key]}') > BigInt.parse('${result[key]}')) {
      result[key] = current[key];
    }
  }
  if (!page.containsKey('unread_count') &&
      (page.containsKey('latest_seq') || page.containsKey('read_seq'))) {
    result['unread_count'] =
        (BigInt.parse('${result['latest_seq']}') -
                BigInt.parse('${result['read_seq']}'))
            .toString();
  }
  // 通知已读由单条状态或账号水位共同决定；历史补页也必须应用已知水位。
  final readThrough = BigInt.parse('${result['read_seq']}');
  for (final value in messages.values) {
    if (BigInt.parse('${value['conversation_seq']}') <= readThrough) {
      value['is_read'] = true;
    }
  }
  return result;
}
