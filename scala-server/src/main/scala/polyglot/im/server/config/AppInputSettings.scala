package polyglot.im.server.config

/** HOCON 配置的类型化读取结果，尚未创建数据库或网络资源。 */
final case class AppInputSettings(
    server: HttpSettings,
    database: DatabaseInputSettings,
    auth: AuthSettings,
    apns: ApnsInputSettings,
    rateLimits: RateLimitInputSettings,
    push: PushWorkerSettings,
    directChat: DirectChatSettings = DirectChatSettings()
)
