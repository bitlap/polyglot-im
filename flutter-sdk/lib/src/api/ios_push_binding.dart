import '../service/push_binding.dart';
import '../adapters/push/method_channel_push.dart';

class IosPushBinding extends PushBindingService {
  IosPushBinding({
    required super.client,
    required super.onOpen,
    required super.onError,
    super.platform = const MethodChannelPush(),
  });
}
