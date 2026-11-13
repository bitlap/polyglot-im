import 'dart:convert';
import 'dart:io';

import 'package:polyglot_im/polyglot_im.dart';
import 'package:test/test.dart';

void main() {
  test('单条已读、删除墓碑与迟到历史共享契约', () {
    final data =
        jsonDecode(
              File(
                '../protocol/fixtures/notification-actions.json',
              ).readAsStringSync(),
            )
            as JsonMap;
    var state = applyPage(emptyCache(), data['initial'] as JsonMap);
    state = applyPage(state, data['read'] as JsonMap);
    expect(state['read_seq'], '0');
    expect(state['unread_count'], '2');
    expect(state['messages']['notice-action']['is_read'], true);
    state = applyPage(state, data['delete'] as JsonMap);
    final stale = <String, dynamic>{'messages': data['initial']['messages']};
    expect(applyPage(state, {...stale, 'history_position': '3'}), state);
    state = applyPage(state, stale);
    expect(state['messages']['notice-action']['is_deleted'], true);
    expect(state['unread_count'], '1');
    expect(
      applyPage(state, {'messages': []}, reset: true)['messages'],
      isEmpty,
    );
  });
  final fixture =
      jsonDecode(File('../protocol/fixtures/message.json').readAsStringSync())
          as Map<String, dynamic>;

  test('共享样例往返编码并保留大序号', () {
    final message = ImMessage.fromJson(fixture);
    expect(message.conversationSeq, '9007199254740993');
    expect(message.toJson(), fixture);
  });

  test('作者审核动作共享契约', () {
    final data =
        jsonDecode(
              File(
                '../protocol/fixtures/review-message.json',
              ).readAsStringSync(),
            )
            as JsonMap;
    expect(ImMessage.fromJson(data).toJson(), data);
    expect(ImMessage.fromJson(data).payload['action']['type'], 'open_review');
  });

  test('拒绝无效序号', () {
    expect(
      () => ImMessage.fromJson({...fixture, 'conversation_seq': '-1'}),
      throwsFormatException,
    );
  });

  test('未知消息类型和字段不会阻断解析', () {
    final message = ImMessage.fromJson({
      ...fixture,
      'type': 'custom.future',
      'future_field': true,
    });
    expect(message.type, 'custom.future');
    expect(message.payload, fixture['payload']);
  });
  test('单聊 Unicode 契约与历史页版本保护', () {
    final data =
        jsonDecode(
              File(
                '../protocol/fixtures/direct-message.json',
              ).readAsStringSync(),
            )
            as JsonMap;
    expect(ImMessage.fromJson(data).toJson(), data);
    final patch = <String, dynamic>{
      'conversation_id': data['conversation_id'],
      'revision': '3',
      'read_seq': '2',
      'messages': [data],
    };
    var state = applyPage(emptyCache(), {
      'events': [
        {'type': 'conversation.updated', 'data': patch},
      ],
    });
    state = applyPage(state, {
      'conversations': [
        {...patch, 'revision': '1', 'read_seq': '0', 'messages': []},
      ],
    });
    final saved = state['conversations']['direct_contract'] as JsonMap;
    expect(saved['read_seq'], '2');
    expect((saved['messages'] as Map).length, 1);
    expect(state['messages'], isEmpty);
    expect(state['cursor'], isNull);
  });
}
