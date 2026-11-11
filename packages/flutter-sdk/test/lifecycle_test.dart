import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:polyglot_im_flutter_sdk/polyglot_im_flutter_sdk.dart';

void main() {
  test('只导入 Flutter SDK 即可访问 Dart 核心模型', () {
    final message = ImMessage.fromJson({
      'schema_version': 1,
      'message_id': 'msg_flutter_entry',
      'conversation_id': 'conv_flutter_entry',
      'conversation_seq': '1',
      'type': 'text',
      'payload': {'text': 'Flutter 单一入口'},
      'created_at': '2026-11-11T08:00:00Z',
    });
    expect(message.payload['text'], 'Flutter 单一入口');
  });

  testWidgets('挂载与释放后正确转发生命周期事件', (tester) async {
    var resumes = 0;
    final observer = ImLifecycleObserver(onResume: () => resumes++);
    observer.attach();
    observer.attach();
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    expect(resumes, 1);
    observer.dispose();
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    expect(resumes, 1);
  });
}
