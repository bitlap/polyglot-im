import 'package:flutter_test/flutter_test.dart';
import 'package:polyglot_im_example/main.dart';
import 'package:flutter/material.dart';

void main() {
  testWidgets('未配置凭证时提供配置提示', (tester) async {
    await tester.pumpWidget(const MaterialApp(home: InboxExample()));
    await tester.pump();
    expect(find.textContaining('本地测试配置'), findsOneWidget);
  });
}
