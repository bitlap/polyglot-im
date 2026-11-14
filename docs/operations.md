# 安全与运维

提供 PostgreSQL + HTTP/WS + 可选 APNs 的可运行实现；上线前需按目标负载验证容量、备份恢复、TLS、机密管理和告警。仓库未提供容量或合规认证承诺。

## 部署与身份

配置见 [HOCON 模板](../config/server.example.conf)。数据库需预先创建，应用启动初始化当前 snap_flow.im_xxx 表结构并校验必要签名配置。使用数据库最小权限账户，备份数据库/签名配置并保护权限；不把真实连接串、.p8、JWT 或 device token 提交仓库。

生产经 HTTPS/WSS 网关接入；反向代理支持 Upgrade、连接空闲时间大于 20 秒心跳。禁止记录 Authorization、APNs token、通知正文及 WS ticket 查询参数。固定 app_id 与用户/服务密钥分离，业务服务签名范围 types/recipients 采用最小权限。不透明身份内省只指向你控制的 HTTPS 服务，不跟随重定向。

鉴权后的请求按 PostgreSQL 原子窗口执行跨实例限流；入口仍应限制未认证流量、连接数、请求体和带宽。HTTP 并发门限默认 256，满载返回 503。数据库连接池默认上限 8，另需每实例一条 LISTEN 直连；WS 提交后唤醒并每 5 秒协调补拉。连接预算、故障语义和压测口径见[多实例部署](distributed.md)。没有默认 Redis 依赖。

## APNs

APNs 使用 HTTP/2 与 ES256 provider JWT 认证。设置 `polyglot-im.apns.enabled=true`，并配置 team-id、key-id、topic、PKCS8 .p8 的 key-file 路径与 sandbox 标志；环境变量仅在 HOCON 显式引用后生效。开发/发布 token 与 endpoint/topic 匹配。默认关闭锁屏正文 preview，静音按设备持久化。token 轮换重新注册时保留已有静音/预览偏好。退出先解绑。仅 `410 / Unregistered` 条件清除本次投递使用的 token，不误清已轮换的新 token；`BadDeviceToken` 和 `DeviceTokenNotForTopic` 保留注册记录，核对设备实际安装版本、签名环境和 topic 后处理。

Worker 每秒最多领取自身并发数（默认 8）的任务并行执行，延迟 3 秒允许站内阅读抑制，租约 60 秒、单任务超时 20 秒、APNs 请求超时 10 秒、最多领取 10 次。中断后租约过期接管，完成回写和 token 清理均验证领取标识，拒绝过期 Worker 写回。监控 im_outbox 的 pending/dead 数量、最早 next_at、attempts/error；dead 修复配置后人工重置为 pending，清零 attempts/lease_until、清除 lease_token 并设置 next_at，保留原 message_id。`429`、`5xx`、网络故障按预算重试；`403 / ExpiredProviderToken` 清除本次过期签名缓存后重试，其余明确拒绝立即进入 dead，保留错误原因。耗尽预算保留最后一次错误。`done` 表示本次被 APNs 接受或投递前已无合适目标，两者分别记录 `APNs accepted` 与 `skipped: no eligible push target`，均不代表设备送达。

拒绝日志记录 `message_id`、HTTP 状态、Apple 标准 reason、环境和 `apns_id`；任务日志通过 `message_id` 关联 `job_id` 与尝试次数。Outbox `error` 同时保存接受结果或错误诊断；字段名沿用现有表结构。未知或非法响应仅记 `UnknownReason`，不记录正文、token 或签名。历史 `device token rejected` 无法恢复为具体 Apple 原因；升级不会自动重发历史任务。恢复前先核对消息是否仍未读、设备注册和配置，避免重复提醒。

Apple 的后台、权限和强退行为受系统控制，应使用自己的测试设备验证。官方依据：[APNs token 认证](https://developer.apple.com/documentation/UserNotifications/establishing-a-token-based-connection-to-apns)、[设备注册](https://developer.apple.com/documentation/usernotifications/registering-your-app-with-apns)。

## 留存和恢复

事件清理入口为 `PostgresStore.purgeEvents(beforeEpochSeconds)`：在用户状态锁内删除过期事件并记录 retained_from，旧客户端将收到 RESET_REQUIRED。默认不自动删除消息或幂等记录；上线时根据产品和法规设置调度及留存周期。SQL 管理操作必须保持该水位语义，不能直接删事件后让客户端跳过缺口。

服务端每分钟有界清理过期 im_ticket 和旧限流计数；done Outbox 可在符合审计/重放政策后清理，pending/dead 不随意删除。消息正文、用户已读水位和幂等键的留存必须一起考虑；删除幂等记录可能允许旧业务重试创建重复通知。生产业务数据库 Outbox 同样监控 pending/dead/租约年龄。

发送超时、缓存失败、WS 中断及事件过期的处理统一见[断线、ack 与离线恢复](delivery.md#断线ack-与离线恢复)。APNs 故障按上节排查配置与死信，站内 HTTP/WS 同步仍可独立工作。

备份演练须验证消息、水位、事件、设备与幂等记录的一致性。数据库恢复回退时应按运维计划使客户端重建缓存，必要时更换环境标识/签名 epoch；不能直接忽略 ahead-of-server 游标错误。RPO/RTO 以实际备份与故障恢复演练为准，吞吐压测不能替代恢复验收。

## 观测

已提供 liveness、真实数据库 readiness、统一错误 request_id，以及 Worker 领取/执行错误日志。生产应在网关/数据库/业务宿主增加发送延迟、WS 连接、鉴权失败、游标重置、Outbox 积压、APNs 错误、数据库锁等待和备份恢复告警。没有内置 Prometheus 指标端点，日志/指标不得输出原始 token。

通知路由仅供服务身份发送；用户单聊使用独立会话路由。服务端不提供任意 URL 抓取。

## 通知操作结构升级

全新数据库使用当前 `db/schema.sql`。已有部署不会自动升级；在首次使用单条已读/删除前，备份数据库并停止所有 IM 写入实例、Push Worker 和生产者，确认 `im_message` 尚无 `is_read/is_deleted/state_version` 且 `im_user_state` 尚无 `notification_unread` 后，由部署者执行 [notification-actions.sql](../scala-server/src/main/resources/db/notification-actions.sql)。脚本单事务加锁、添加字段和索引，并按原 read_seq 回填未读数；仅适用该字段缺失的当前基线，不是通用迁移链。

执行后核对每用户 `notification_unread` 等于未删除、未单条已读且 seq 大于 read_seq 的数量，再启动新服务。所有宿主同时升级 SDK 后启用新操作；保留未升级宿主会产生错误的未读展示。回滚应用前先停用新操作，旧服务不得与新服务混用；数据库回退使用已验证的备份，不能直接丢弃有状态字段。上线后检查单条已读、删除、设备间同步和角标。代码测试不代表已完成生产升级。

## 2026-11-14 部署记录

服务端 0.1.1 运行源码 `7f7a3f2456e6b35797905d647a261d212e82347d`（PR #53），发布归档 SHA-256 `5fbcdf4afe919516b05f9674248508e73a6360c13d390401a35c96ce411ba68e`。类路径切换至 `/home/snap/releases/polyglot-im/20261114-7f7a3f2/im/lib/*`，私有配置和 APNs 私钥校验值保持不变，原工作目录保留，无数据库结构修改或历史任务重发。服务 active、NRestarts=0，就绪检查成功。

PR 云端六类检查通过，最终源码干净编译及运行类路径打包通过。独立 IM 数据库备份位于服务器受限目录 `/home/snap/deploy-backups/release34-20261114/backup/`；备份 66,650 字节，SHA-256 `e2bb58918e8637921fd0b86a37d9de803f6a16f3b5c08651c4f5d0fc58867307`，目录清单验证通过。原服务 unit 已保存，回退二进制不回写数据库。真实设备投递仍需以新任务的 Apple 响应和设备表现核验，历史泛化错误原因无法恢复。
