package polyglot.im.server.config

/** 显式启用单聊控制及可信业务目录；默认关闭以保持旧部署的文字单聊行为。 */
final case class DirectChatSettings(enabled: Boolean = false, directoryUrl: Option[String] = None) {

  /** 在线租约最多维持一分钟，客户端前台定期续约。 */
  val presenceSeconds: Long = 60L

  /** 允许本人按数据库时间在两分钟内撤回，离线客户端不能延长窗口。 */
  val recallSeconds: Long = 120L
}
