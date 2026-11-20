package polyglot.im.server.config

/** HOCON 推送开关及 APNs 身份字段；启用时校验完整性。 */
final case class ApnsInputSettings(
    enabled: Boolean,
    teamId: Option[String],
    keyId: Option[String],
    topic: Option[String],
    keyFile: Option[String],
    sandbox: Boolean
)
