import '../service/push_binding.dart';
import '../adapters/push/method_channel_push.dart';

/// 装配客户端、原生推送端口和通知点击回调的 Flutter 入口。
class IosPushBinding extends PushBindingService {
  /// 绑定客户端和通知打开/错误回调，可注入 IosPushPlatform 以替换原生通道。
  IosPushBinding({
    required super.client,
    required super.onOpen,
    required super.onError,
    super.platform = const MethodChannelPush(),
  });
}
