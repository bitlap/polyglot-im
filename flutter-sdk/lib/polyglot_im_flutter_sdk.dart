library;

import 'package:flutter/widgets.dart';

export 'package:polyglot_im_dart_sdk/polyglot_im_dart_sdk.dart';
export 'src/sqlite_store.dart';
export 'src/apns.dart';
export 'src/inbox.dart';
export 'src/direct_chat.dart';
export 'src/conversations.dart';

/// 只转发生命周期恢复事件；网络同步和错误处理由调用方负责。
final class ImLifecycleObserver extends WidgetsBindingObserver {
  ImLifecycleObserver({required this.onResume});

  final VoidCallback onResume;
  bool _attached = false;

  void attach() {
    if (_attached) return;
    WidgetsBinding.instance.addObserver(this);
    _attached = true;
  }

  void dispose() {
    if (!_attached) return;
    WidgetsBinding.instance.removeObserver(this);
    _attached = false;
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (_attached && state == AppLifecycleState.resumed) onResume();
  }
}
