import 'package:flutter/widgets.dart';

/// 只转发生命周期恢复事件；网络同步和错误处理由调用方负责。
final class ImLifecycleObserver extends WidgetsBindingObserver {
  /// 保存恢复前台时的补拉回调；调用 attach 后才开始监听。
  ImLifecycleObserver({required this.onResume});

  final VoidCallback onResume;
  bool _attached = false;

  /// 注册生命周期与事件订阅；重复调用不重复挂载。
  void attach() {
    if (_attached) return;
    WidgetsBinding.instance.addObserver(this);
    _attached = true;
  }

  /// 解除订阅并释放本组件持有的资源，迟到回调不再更新界面。
  void dispose() {
    if (!_attached) return;
    WidgetsBinding.instance.removeObserver(this);
    _attached = false;
  }

  /// 恢复前台时触发 onResume；其他生命周期状态不执行补拉回调。
  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (_attached && state == AppLifecycleState.resumed) onResume();
  }
}
