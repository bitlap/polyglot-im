import 'dart:convert';
import '../common/types.dart';
import 'message.dart';

JsonMap emptyCache() => {
  'messages': <String, dynamic>{},
  'cursor': null,
  'event_position': '0',
  'latest_seq': '0',
  'read_seq': '0',
  'unread_count': '0',
  'chat_unread_count': '0',
};

JsonMap applyPage(JsonMap current, JsonMap page, {bool reset = false}) {
  if (BigInt.parse('${page['event_position'] ?? current['event_position']}') <
      BigInt.parse('${current['event_position']}')) {
    return current;
  }
  final result = reset
      ? emptyCache()
      : jsonDecode(jsonEncode(current)) as JsonMap;
  final messages = result['messages'] as JsonMap;
  void add(dynamic value) {
    final message = ImMessage.fromJson(Map<String, dynamic>.from(value as Map));
    messages[message.messageId] = message.toJson();
  }

  for (final value in (page['messages'] as List? ?? const [])) {
    add(value);
  }
  for (final event in (page['events'] as List? ?? const [])) {
    if (event['type'] == 'message.created') add(event['data']);
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
  result['unread_count'] =
      (BigInt.parse('${result['latest_seq']}') -
              BigInt.parse('${result['read_seq']}'))
          .toString();
  return result;
}
