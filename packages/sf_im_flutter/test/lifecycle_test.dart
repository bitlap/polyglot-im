import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sf_im_flutter/sf_im_flutter.dart';

void main() {
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
