import '../common/types.dart';
import '../domain/account_scope.dart';

/// 按账号作用域保存消息和同步水位；合并与游标更新必须原子提交。
abstract interface class LocalStore {
  /// 读取指定账号作用域的缓存；尚无记录时返回空状态。
  Future<JsonMap> load(AccountScope scope);

  /// 原子合并消息和游标并返回提交后的状态；reset 表示用快照重建缓存。
  Future<JsonMap> commit(
    AccountScope scope,
    JsonMap page, {
    bool reset = false,
  });

  /// 只清除指定账号作用域的本地缓存，不删除服务器消息。
  Future<void> clear(AccountScope scope);
}
