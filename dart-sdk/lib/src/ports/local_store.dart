import '../common/types.dart';
import '../domain/account_scope.dart';

abstract interface class LocalStore {
  Future<JsonMap> load(AccountScope scope);
  Future<JsonMap> commit(
    AccountScope scope,
    JsonMap page, {
    bool reset = false,
  });
  Future<void> clear(AccountScope scope);
}
