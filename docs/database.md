# 数据表与源码入口

表清单统一在本页维护。每张表和每个字段的含义直接注释在对应建表源码中，不重复维护字段字典，不生成数据库 `COMMENT ON`。

## IM 服务端 PostgreSQL

服务端共15张表，均位于 `snap_flow`。除 `im_schema` 外，完整初始结构及逐字段注释在 [schema.sql](../scala-server/src/main/resources/db/schema.sql)；`im_schema` 及其 `version` 字段的注释在 [PostgresRepository.scala](../scala-server/src/main/scala/polyglot/im/server/infrastructure/postgres/PostgresRepository.scala) 的 `initialize`。

| 表 | 职责 |
| --- | --- |
| `snap_flow.im_schema` | 初始化完成标记，version=1 不是迁移版本号 |
| `snap_flow.im_user_state` | 用户事件游标、通知水位、通知与单聊未读总数 |
| `snap_flow.im_message` | 通知消息、发送幂等与个人已读／删除状态 |
| `snap_flow.im_event` | 按用户持久化的增量事件流 |
| `snap_flow.im_device` | 设备注册、APNs token、偏好及落盘确认 |
| `snap_flow.im_outbox` | 服务端按设备领取的 APNs 投递任务 |
| `snap_flow.im_ticket` | WebSocket 一次性短期票据摘要 |
| `snap_flow.im_rate_limit` | 跨实例限流窗口计数 |
| `snap_flow.im_direct_conversation` | 双人会话、双方水位及未读计数 |
| `snap_flow.im_direct_message` | 单聊消息事实、幂等与撤回状态版本 |
| `snap_flow.im_direct_settings` | 用户级接收与在线可见范围 |
| `snap_flow.im_direct_preference` | 本人对某用户的偏好及隐藏／清空边界 |
| `snap_flow.im_direct_deleted` | 个人删除墓碑，不删除对方副本 |
| `snap_flow.im_presence` | 设备在线租约 |
| `snap_flow.im_direct_activation` | 持久激活标记，阻止关闭控制绕过权限 |

已有数据库的升级单独维护：

- [notification-actions.sql](../scala-server/src/main/resources/db/notification-actions.sql)：通知个人状态、未读计数回填及索引，包含新增字段注释。
- [direct-controls.sql](../scala-server/src/main/resources/db/direct-controls.sql)：单聊消息状态版本和五张控制表，包含表及字段注释。

脚本不是可任意重跑的通用迁移链。生产启用、停写与备份要求见[运维说明](operations.md)，本页不授权执行迁移。`BIGINT` 序号与时间戳不同，时间单位以字段源码注释为准。

## Client Cache 与业务 Store

这些表由宿主显式选择和初始化，不属于 IM 服务端15张表。不同实现保留已有表名，不因注释整理而改名或共享缓存格式。

| 表／用途 | 含表与逐字段注释的源码 |
| --- | --- |
| PostgreSQL `im_client_cache`：namespace、scope、data | [Scala](../scala-client/src/main/scala/polyglot/im/infrastructure/cache/PostgresCache.scala)、[Python](../python-client/src/polyglot_im/adapters/cache/postgres_cache.py)、[Dart VM](../dart-client/lib/src/adapters/cache/postgres_cache.dart) |
| SQLite `im_cache`：scope、data | [Scala](../scala-client/src/main/scala/polyglot/im/infrastructure/cache/SqliteCache.scala)、[Dart VM](../dart-client/lib/src/adapters/cache/sqlite_cache.dart)、[Flutter](../flutter-client/lib/src/adapters/cache/sqlite_cache.dart) |
| Python SQLite `cache`：scope、data | [Python](../python-client/src/polyglot_im/adapters/cache/sqlite_cache.py) |
| Flutter SQLite `im_installation`：id | [Flutter](../flutter-client/lib/src/adapters/cache/sqlite_cache.dart) |
| PostgreSQL `im_producer_outbox`：业务事务通知意图 | [Scala](../scala-client/src/main/scala/polyglot/im/infrastructure/store/PostgresOutbox.scala)、[Python](../python-client/src/polyglot_im/adapters/store/postgres_outbox.py) |

PostgreSQL Cache 的默认 schema 为 `snap_flow`，三端可配置；Python Outbox 也可配置 schema，Scala Outbox 固定为 `snap_flow`。`im_producer_outbox` 位于业务宿主数据库，与服务端 `im_outbox` 分离。Flutter、uni-app不提供PostgreSQL连接入口。uni-app使用uni持久化，无本项目定义的SQL表。文件与内存缓存也没有SQL表。隔离、事务及资源所有权见[客户端存储](client/storage.md)。
