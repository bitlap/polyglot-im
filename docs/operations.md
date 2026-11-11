# 安全与运维

提供 PostgreSQL + HTTP/WS + 可选 APNs 的可运行实现；上线前需按目标负载验证容量、备份恢复、TLS、机密管理和告警。仓库未提供容量或合规认证承诺。

## 部署与身份

配置见 [HOCON 模板](../config/server.example.conf)。数据库需预先创建，应用启动初始化当前 snap_flow.im_xxx 表结构并校验必要签名配置。使用数据库最小权限账户，备份数据库/签名配置并保护权限；不把真实连接串、.p8、JWT 或 device token 提交仓库。

生产经 HTTPS/WSS 网关接入；反向代理支持 Upgrade、连接空闲时间大于 20 秒心跳。禁止记录 Authorization、APNs token、通知正文及 WS ticket 查询参数。固定 app_id 与用户/服务密钥分离，业务服务签名范围 types/recipients 采用最小权限。不透明身份内省只指向你控制的 HTTPS 服务，不跟随重定向。

鉴权后的请求按 PostgreSQL 原子窗口执行跨实例限流；入口仍应限制未认证流量、连接数、请求体和带宽。HTTP 并发门限默认 256，满载返回 503。数据库连接池默认上限 8，另需每实例一条 LISTEN 直连；WS 提交后唤醒并每 5 秒协调补拉。连接预算、故障语义和压测口径见[多实例部署](distributed.md)。没有默认 Redis 依赖。

## APNs

开启 POLYGLOT_IM_APNS_ENABLED 后注入 Team/Key ID、topic、PKCS8 .p8 路径和 sandbox 标志。开发/发布 token 与 endpoint/topic 匹配。默认关闭锁屏正文 preview，静音按设备持久化。token 轮换重新注册时保留已有静音/预览偏好。退出先解绑，失效 token 条件清除，不能误清刚轮换的新 token。

Worker 每秒最多领取自身并发数（默认 8）的任务并行执行，延迟 3 秒允许站内阅读抑制，租约 60 秒、单任务超时 20 秒、APNs 请求超时 10 秒、最多领取 10 次。中断后租约过期接管，完成回写和 token 清理均验证领取标识，拒绝过期 Worker 写回。监控 im_outbox 的 pending/dead 数量、最早 next_at、attempts/error；dead 修复配置后人工重置为 pending，清零 attempts/lease_until、清除 lease_token 并设置 next_at，保留原 message_id。Provider 接受只表示 Apple 接收请求，不表示设备送达或用户阅读。

Apple 的后台、权限和强退行为受系统控制，应使用自己的测试设备验证。官方依据：[APNs token 认证](https://developer.apple.com/documentation/UserNotifications/establishing-a-token-based-connection-to-apns)、[设备注册](https://developer.apple.com/documentation/usernotifications/registering-your-app-with-apns)。

## 留存和恢复

事件清理入口为 `PostgresStore.purgeEvents(beforeEpochSeconds)`：在用户状态锁内删除过期事件并记录 retained_from，旧客户端将收到 RESET_REQUIRED。默认不自动删除消息或幂等记录；上线时根据产品和法规设置调度及留存周期。SQL 管理操作必须保持该水位语义，不能直接删事件后让客户端跳过缺口。

服务端每分钟有界清理过期 im_ticket 和旧限流计数；done Outbox 可在符合审计/重放政策后清理，pending/dead 不随意删除。消息正文、用户已读水位和幂等键的留存必须一起考虑；删除幂等记录可能允许旧业务重试创建重复通知。生产业务数据库 Outbox 同样监控 pending/dead/租约年龄。

| 故障 | 恢复路径 |
| --- | --- |
| API 提交后响应丢失 | 使用相同幂等键重试 |
| Worker 崩溃 | 租约到期重新领取，标识隔离旧回写；外部投递仍可能重复 |
| 网关重启 / WS 中断 | Client 先 HTTP 追增量，再连接持久流 |
| 本地缓存写失败 | 不 ack，恢复后从旧游标重放 |
| 事件留存清理 | RESET_REQUIRED，快照 + 旧历史分页 |
| APNs 故障 / 权限拒绝 | 站内 HTTP/WS 仍工作；推送重试或死信 |

备份演练须验证消息、水位、事件、设备与幂等记录的一致性。数据库恢复回退时应按运维计划使客户端重建缓存，必要时更换环境标识/签名 epoch；不能直接忽略 ahead-of-server 游标错误。未压测前不提供 RPO/RTO 数字。

## 观测

已提供 liveness、真实数据库 readiness、统一错误 request_id，以及 Worker 领取/执行错误日志。生产应在网关/数据库/业务宿主增加发送延迟、WS 连接、鉴权失败、游标重置、Outbox 积压、APNs 错误、数据库锁等待和备份恢复告警。没有内置 Prometheus 指标端点，日志/指标不得输出原始 token。

后续功能统一跟踪 [Issue 路线图](https://github.com/bitlap/polyglot-im/issues/3)。通知路由不开放用户聊天发送或任意 URL 抓取。
