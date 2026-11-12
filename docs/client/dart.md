# Dart Client 存储集成

纯 Dart 工程依赖 `polyglot_im`，核心 API 与存储接口由主入口导出。本地 SQLite 使用独立入口 `sqlite.dart`，不重导出到 Flutter Client，避免与 Flutter 平台的 SqliteStore 混淆。

```dart
import 'package:polyglot_im/polyglot_im.dart';
import 'package:polyglot_im/sqlite.dart';

final store = await SqliteStore.open('/app/data/im.sqlite');
final client = ImClient(
  api: HttpApi(baseUrl: baseUrl, tokenProvider: readUserToken),
  scope: AccountScope(environment: baseUrl, appId: appId, userId: userId),
  deviceId: deviceId,
  store: store,
);
try {
  await client.initialize();
  await client.sync();
} finally {
  await client.close();
  await store.close();
}
```

宿主选择持久文件路径并提供稳定安装 ID、用户凭证与 scope；父目录自动创建。空路径、`:memory:` 与 `file:` URI 被拒绝，避免误用连接关闭即丢失的内存库。重启复用相同路径和 scope；缓存不保存 Bearer。账号切换应关闭旧客户端，并用新 scope 创建客户端。接口和生命周期通用约定见[Client 总览](README.md)。

## 事务、并发与释放

适配器通过 [sqflite_common_ffi](https://pub.dev/packages/sqflite_common_ffi) 在后台 isolate 执行 SQL，使用其局部 factory，不修改全局 databaseFactory。每个 open 创建独占连接，宿主在所有使用该连接的客户端停止后 close；重复关闭安全，已接受的写入完成后才关闭，关闭后访问报错。同一存储可供多个账号 scope 使用。

采用 WAL、synchronous=FULL 和 15 秒 busy_timeout；普通 load 不开启写事务。commit 在写事务内读取当前 scope、归并并原子提交消息、会话及游标，校验、SQL 或提交失败回滚；clear 不解析损坏 JSON，只删除目标 scope。存储失败向调用方抛出，未落盘的数据不会进入成功 ack 路径。

同一 Dart isolate 内按规范化的真实文件路径协调多个连接的初始化、写事务及关闭，避免共享后台 SQL isolate 的连接互相阻塞提交；最后一个连接关闭后释放协调记录。跨进程/独立 Dart isolate 由 SQLite 文件锁仲裁，锁等待失败会报错，不无限重试或重放事务。宿主若直接使用公开 database 诊断句柄，应自行管理其事务，避免与 SDK 写入并发。

当前按 scope 保存 JSON 缓存；格式与性能边界见[客户端存储](storage.md#数据格式与性能边界)。目录权限、备份和磁盘加密由宿主配置；本地 SQLite 与下节 PostgreSQL 是独立可选后端。

## PostgreSQL 客户端缓存

可信 Dart VM 宿主可通过独立入口 postgres.dart 使用 PostgresStore，Flutter 主入口不重导出该适配器：

```dart
import 'package:polyglot_im/postgres.dart';

final store = await PostgresStore.open(
  postgresUrl, namespace: 'desktop-installation',
);
try {
  // 将 store 注入同一 ImClient；先停止客户端，再关闭存储。
} finally {
  await store.close();
}
```

URL 必须显式提供协议、主机和数据库；TLS、连接/查询超时、池大小由 [postgres 驱动](https://pub.dev/packages/postgres) URL 配置。open 创建并拥有池、自动初始化 schema/表，初始化失败也关闭池。已有 `Pool<void>` 可通过 `PostgresStore(pool, namespace: ...)` 借用并显式 `await store.initialize()`；此时 store.close 不关闭宿主池，重复关闭安全，关闭后不能复用该 store。

消息、会话与游标在 READ COMMITTED 事务中持有作用域锁后归并，失败回滚；lockTimeout 默认 15 秒，schema 默认 snap_flow，缓存表 im_client_cache。普通读取不占写锁；clear 同样串行且不解析损坏 JSON。namespace、资源所有权与性能边界见[客户端存储](storage.md)。实际接入及真实临时 PostgreSQL 回归见 [postgres_storage.dart](../../examples/dart-client/postgres_storage.dart)，不把消息缓存等同于业务 Outbox。

## 平台与验收边界

SQLite FFI 与 PostgreSQL 入口均面向 Dart VM，不支持浏览器；Web 宿主继续实现 LocalStore。Flutter App 使用 Flutter Client 的现有 sqflite 平台适配器、openDefault 和 installationId，不需要改为此 FFI 入口。VolatileStore 仅用于测试或明确接受重启丢缓存的降级场景。

FFI 依赖使用 sqlite3 构建 hooks，运行示例使用 `dart run`，测试使用 `dart test`；原生库配置遵循上述依赖文档。仓库真实临时文件与网络验证以当前机器为准，不将 macOS 验证等同于 Windows/Linux 真机验收。网络收发、SQLite 重启恢复与清理示例见 [Dart 示例](../../examples/dart-client/README.md)；UI/APNs 接入见 [Flutter 集成](flutter.md)。
