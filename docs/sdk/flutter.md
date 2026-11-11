# Flutter / Dart SDK 集成

## 当前依赖

当前没有 pub.dev 发布版本，使用本地路径；以下示例假设 App 和 sf-im 位于同一父目录。路径需要按自己的工程调整。

```yaml
dependencies:
  sf_im_flutter:
    path: ../sf-im/packages/sf_im_flutter
```

纯 Dart 工程可只依赖 `../sf-im/packages/sf_im_client`。要求 Dart 3.12+；Flutter 适配包要求 Flutter 3.44+。

## 当前消息编解码

```dart
import 'dart:convert';
import 'package:sf_im_flutter/sf_im_flutter.dart';

final message = ImMessage.fromJson({
  'schema_version': 1,
  'message_id': 'msg_001',
  'conversation_id': 'conv_001',
  'conversation_seq': '1',
  'type': 'text',
  'payload': {'text': '你好'},
  'created_at': '2026-11-11T08:00:00Z',
});
final encoded = jsonEncode(message.toJson());
```

序号保持 String；需要比较时使用 BigInt 或服务端游标逻辑，不能按字符串字典序比较。当前模型会忽略未知信封字段，保留 payload 对象。非法输入可能产生 FormatException 或类型错误，宿主应统一捕获并显示安全占位。

## 当前生命周期适配

宿主先执行 WidgetsFlutterBinding.ensureInitialized()。在拥有该订阅的 State.initState 中创建 observer 并 attach，在 State.dispose 中 dispose：

```dart
final observer = ImLifecycleObserver(
  onResume: () {
    // 在此触发宿主自己的刷新；SDK 网络同步尚未实现。
  },
);
observer.attach();
// 页面或应用容器释放时：
observer.dispose();
```

observer 只转发恢复事件，不会自动建立长连接、创建本地库、注册推送或调用异步 sync。异步回调应由宿主包装并处理错误。

## sf-app 迁移（规划）

1. AppState 持有与当前身份绑定的 ImClient；页面只订阅本地会话和消息视图。
2. 现有 Notice.messageId 映射为消息身份，content 迁移为 text 或 notification payload。
3. 原 `/api/v1/message/list` 与新接口从同一存储读取，迁移期通过服务端适配，避免重复通知。
4. 首页红点订阅服务端确认的未读计数；打开消息页不应无条件把用户未看到的分页消息标为已读。
5. 切换账号或环境时取消旧任务代次，隔离数据库、发送队列和通知点击目标。

## 存储、推送与平台（规划）

移动/桌面使用 SQLite，Web 使用 IndexedDB 适配。当前没有对应依赖和实现；不能仅凭包可分析就声明平台能力完成。

iOS 对接 APNs，Android 依据发行环境选择 FCM/厂商推送。权限申请由宿主选择合适时机；后台 handler 须遵循平台隔离限制。token 轮换同步到服务端，退出解绑。通知点击先验证账号、恢复同步，再交由宿主受控导航，不直接执行服务端提供的任意 URL。

前台同一消息经 WS 和推送重复到达时以 messageId/eventId 去重；系统托盘提醒与 App 内提醒避免重复展示。移动强退、权限拒绝和后台限制需要真机验收。
