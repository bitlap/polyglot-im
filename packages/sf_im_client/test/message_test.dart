import 'dart:convert';
import 'dart:io';

import 'package:sf_im_client/sf_im_client.dart';
import 'package:test/test.dart';

void main() {
  final fixture =
      jsonDecode(
            File('../../protocol/fixtures/message.json').readAsStringSync(),
          )
          as Map<String, dynamic>;

  test('共享样例往返编码并保留大序号', () {
    final message = ImMessage.fromJson(fixture);
    expect(message.conversationSeq, '9007199254740993');
    expect(message.toJson(), fixture);
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
}
