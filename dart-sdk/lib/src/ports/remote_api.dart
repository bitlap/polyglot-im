import '../common/types.dart';
import '../domain/account_scope.dart';

abstract interface class RemoteApi {
  String get baseUrl;
  void bindScope(AccountScope scope);
  Future<JsonMap> request(String method, String path, {JsonMap? body});
  void close();
}
