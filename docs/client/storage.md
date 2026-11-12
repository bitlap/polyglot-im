# 客户端存储

所有客户端都提供本地持久存储。Python、Scala、Dart VM 另外提供可注入的 PostgreSQL 客户端缓存；uni-app 与 Flutter 不提供 PostgreSQL 适配入口。存储由宿主显式选择，核心通过 LocalStore（uni-app 为 Storage）使用，不创建全局数据库连接或自动切换后端。

| 客户端 | 本地持久存储 | PostgreSQL 消息缓存 | 生命周期 |
| --- | --- | --- | --- |
| Python | SqliteStore，标准库 sqlite3 | PostgresStore | SQLite 每次操作独立连接；PostgresStore 借用宿主池，open_postgres_store 上下文拥有池 |
| Scala | SqliteStore，可选 AtomicFileStore | PostgresStore[F] | SQLite 每次操作独立连接；PostgreSQL 借用 DataSource，宿主使用 Resource 管理池 |
| Dart VM | sqlite.dart 的 SqliteStore | postgres.dart 的 PostgresStore | open 创建独占 SQLite 连接或自有 PostgreSQL 池；构造 PostgresStore 可借用池；宿主显式 close |
| Flutter | 平台 sqflite 的 SqliteStore | 不提供 | 宿主 openDefault/open，客户端停止后 close |
| uni-app | createUniStorage，uni 持久化 | 不提供 | 宿主注入 Storage；按账号保存，配额或写入失败向上抛出 |

VolatileStore 与内存测试替身不是可靠本地持久化。Flutter 主入口只重导出 Dart 核心，不重导出 Dart 的 PostgreSQL/FFI 存储入口；App 与小程序不配置数据库凭证。Python/Scala/Dart 的 PostgreSQL 适用于宿主明确配置的可信运行环境，不经 IM 服务端代理连接。

## PostgreSQL 缓存与事务

默认表为 `snap_flow.im_client_cache(namespace TEXT, scope TEXT, data TEXT)`，主键为 `(namespace,scope)`；data 保存当前客户端缓存 JSON。它与 IM 服务端事实表、生产端 `im_producer_outbox` 分开，不承担业务通知 Outbox 的职责。schema 可配置，仅接受受限 PostgreSQL 标识符；凭证、TLS、池大小、连接/查询超时由宿主管理。

namespace 必须显式传入，长度 1–200 字符，用于区分不同缓存使用者。为独立安装或任务消费者配置稳定 namespace；scope 继续隔离环境、应用与用户。该隔离是 SDK 查询约束，不替代数据库账号权限或业务鉴权；不同客户端格式不保证可共享同一 namespace 的缓存数据。

initialize 显式创建 schema/表并在事务中取得 DDL 锁，Dart/Python 自有池工厂会自动初始化。生产环境可由部署过程预建表，借用池的适配器无需在每次读写时重复建表。普通 load 不获取写锁；commit/clear 在 READ COMMITTED 事务中取得按 namespace/scope 派生的 PostgreSQL 事务级 advisory lock，使首次写入、并发归并、清理和重建同样串行，事务结束自动释放锁。每个作用域的消息、会话和游标统一提交，校验或 SQL 失败全部回滚，损坏 JSON 不妨碍清理。

锁等待默认 15 秒，可传正数调整；连接池获取、连接和查询超时使用宿主/驱动配置。存储异常向调用方抛出，客户端只有提交成功后才 ack；不会在异常时悄悄改用另一种存储。Python 执行中取消交由异步驱动和事务上下文清理；取消若发生在提交确认附近，不能据此断定未落盘，应通过 load/同步恢复。Scala 的 JDBC 在 Async.blocking 中执行，取消不强行中断已开始的事务，完成后的缓存仍可在重试时恢复；Dart 使用驱动事务和池生命周期，宿主应先停止客户端再关闭存储。

## 数据格式与性能边界

SQLite 与 PostgreSQL 适配器均按 scope 保存缓存 JSON，保留字符串 ID、大序号和未知 payload。数据库主键支持按 namespace/scope 精确查询，PostgreSQL 不同作用域可并行；同一作用域归并必须串行。当前未实现逐消息 SQL 索引、数据库级历史分页或跨语言共享缓存文件/记录格式，缓存变大时整份 JSON 的读写与归并成本也会增大。后续替换存储仍须满足同一接口的隔离、原子提交、失败回滚和落盘后 ack 语义。

接入示例：[Python](python.md)、[Scala](scala.md)、[Dart](dart.md)、[Flutter](flutter.md)、[uni-app](uniapp.md)。数据库缓存不会代替消息传输、同步、鉴权或 APNs；消息 UI 和业务资源跳转仍由宿主处理。
