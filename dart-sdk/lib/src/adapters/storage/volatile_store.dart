import '../../common/types.dart';
import '../../domain/account_scope.dart';
import '../../domain/cache.dart';
import '../../ports/local_store.dart';

/// 测试或显式选择的易失缓存；生产 App 应注入 Flutter SDK 的 SQLite 实现。
class VolatileStore implements LocalStore {
  final _values = <String, JsonMap>{};
  @override
  Future<JsonMap> load(AccountScope scope) async =>
      _values[scope.key] ?? emptyCache();
  @override
  Future<JsonMap> commit(
    AccountScope scope,
    JsonMap page, {
    bool reset = false,
  }) async =>
      _values[scope.key] = applyPage(await load(scope), page, reset: reset);
  @override
  Future<void> clear(AccountScope scope) async {
    _values.remove(scope.key);
  }
}
