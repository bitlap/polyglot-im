import '../../common/types.dart';
import '../../domain/account_scope.dart';
import '../../domain/cache.dart';
import '../../ports/local_store.dart';

/// 测试或显式选择的易失缓存；生产 App 应注入 Flutter Client 的 SQLite 实现。
class VolatileStore implements LocalStore {
  final _values = <String, JsonMap>{};

  /// 读取指定账号作用域的缓存；尚无记录时返回空状态。
  @override
  Future<JsonMap> load(AccountScope scope) async =>
      _values[scope.key] ?? emptyCache();

  /// 原子合并消息和游标并返回提交后的状态；reset 表示用快照重建缓存。
  @override
  Future<JsonMap> commit(
    AccountScope scope,
    JsonMap page, {
    bool reset = false,
  }) async =>
      _values[scope.key] = applyPage(await load(scope), page, reset: reset);

  /// 只清除指定账号作用域的本地缓存，不删除服务器消息。
  @override
  Future<void> clear(AccountScope scope) async {
    _values.remove(scope.key);
  }
}
