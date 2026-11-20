package polyglot.im.server.config

/** 应用命名空间、签发者、独立签名密钥及可选身份内省地址；输出脱敏。 */
final case class AuthSettings(
    appId: String,
    issuer: String,
    userJwtSecret: String,
    serviceJwtSecret: String,
    cursorSecret: String,
    introspectionUrl: Option[String]
) {

  /** 返回供诊断使用的文本表示；配置类型的实现会隐藏敏感字段。 */
  override def toString: String = "AuthSettings(<redacted>)"
}
