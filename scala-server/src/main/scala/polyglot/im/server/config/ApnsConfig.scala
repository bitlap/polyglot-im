package polyglot.im.server.config

import java.nio.file.Path

final case class ApnsConfig(team: String, keyId: String, topic: String, keyFile: Path, sandbox: Boolean)
