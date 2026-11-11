import '../common/types.dart';
import '../domain/account_scope.dart';

/// IM 业务请求端口；路径相对统一 API 前缀，身份绑定不能作为认证凭据。
abstract interface class RemoteApi {
  /// 返回已规范化的服务地址，用于验证缓存环境与请求目标一致。
  String get baseUrl;

  /// 绑定当前账号期望身份，后续请求由服务端核对真实凭证。
  void bindScope(AccountScope scope);

  /// 执行相对 IM API 的业务请求并返回解包对象；业务或传输失败向调用方传递。
  Future<JsonMap> request(String method, String path, {JsonMap? body});

  /// 释放此 API 入口拥有的传输资源；借用资源由宿主关闭。
  void close();
}
