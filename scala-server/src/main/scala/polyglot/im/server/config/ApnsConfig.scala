package polyglot.im.server.config

import java.nio.file.Path

/** APNs Provider 的签名身份、topic、私钥路径和开发渠道设置。 */
final case class ApnsConfig(team: String, keyId: String, topic: String, keyFile: Path, sandbox: Boolean)
