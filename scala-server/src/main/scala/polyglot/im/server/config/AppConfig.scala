package polyglot.im.server.config

import polyglot.im.server.domain.Protocol

import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*
import scala.util.Try
import cats.syntax.all.*
import com.comcast.ip4s.{Host, Port}
import com.typesafe.config.{Config, ConfigFactory, ConfigParseOptions}
import pureconfig.{ConfigReader, ConfigSource}
import pureconfig.error.{ConvertFailure, KeyNotFound}
import pureconfig.generic.ProductHint
import pureconfig.generic.semiauto.deriveReader

/** HOCON 中的监听地址、端口及 HTTP 在途请求上限。 */
final case class HttpSettings(host: String, port: Int, maxInFlight: Int)

/** HOCON 数据库与超时字段；输出时隐藏连接信息。 */
final case class DatabaseSettings(
    url: String,
    user: String,
    password: String,
    poolSize: Int,
    connectionTimeout: FiniteDuration,
    statementTimeout: FiniteDuration,
    lockTimeout: FiniteDuration
) {

  /** 返回供诊断使用的文本表示；配置类型的实现会隐藏敏感字段。 */
  override def toString: String = "DatabaseSettings(<redacted>)"
}

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

/** HOCON 推送开关及 APNs 身份字段；启用时校验完整性。 */
final case class ApnsSettings(
    enabled: Boolean,
    teamId: Option[String],
    keyId: Option[String],
    topic: Option[String],
    keyFile: Option[String],
    sandbox: Boolean
)

/** HOCON 中用户与服务身份的每分钟请求配额。 */
final case class RateLimitSettings(userPerMinute: Int, servicePerMinute: Int)

/** HOCON 配置的类型化读取结果，尚未创建数据库或网络资源。 */
final case class AppSettings(
    server: HttpSettings,
    database: DatabaseSettings,
    auth: AuthSettings,
    apns: ApnsSettings,
    rateLimits: RateLimitSettings,
    push: PushWorkerSettings
)

/** 完成校验的服务配置；只有组合根负责加载和注入具体资源。 */
final case class AppConfig(
    server: ServerConfig,
    database: DatabaseConfig,
    auth: AuthSettings,
    introspection: Option[URI],
    apns: Option[ApnsConfig],
    rateLimits: RateLimits,
    maxInFlight: Int,
    push: PushWorkerSettings
)

/** 解析配置来源并读取 HOCON，在创建外部资源前完成字段与密钥校验。 */
object AppConfig {
  private given [A]: ProductHint[A]              = ProductHint[A](allowUnknownKeys = false)
  private given ConfigReader[HttpSettings]       = deriveReader
  private given ConfigReader[DatabaseSettings]   = deriveReader
  private given ConfigReader[AuthSettings]       = deriveReader
  private given ConfigReader[ApnsSettings]       = deriveReader
  private given ConfigReader[RateLimitSettings]  = deriveReader
  private given ConfigReader[PushWorkerSettings] = deriveReader
  private given ConfigReader[AppSettings]        = deriveReader

  /** 解析可选 --config 文件路径；未知参数返回使用错误。 */
  def location(args: List[String]): Either[String, Option[Path]] = args match {
    case Nil => Right(None)
    case List("--config", path) if path.trim.nonEmpty =>
      Try(Some(Path.of(path))).toEither.left.map(_ => "invalid config file path")
    case _ => Left("usage: run [--config /path/application.conf]")
  }

  /** JVM 属性 > 应用配置 > reference 默认值；仅组合根调用。 */
  def load(
      file: Option[Path] = None,
      env: Map[String, String] = sys.env,
      properties: Map[String, String] = sys.props.toMap
  ): Either[String, AppConfig] = {
    val selected = Try(
      file.orElse(properties.get("config.file").map(Path.of(_))).orElse(env.get("POLYGLOT_IM_CONFIG").map(Path.of(_)))
    ).toEither.left.map(_ => "invalid config file path")
    selected.flatMap { path =>
      val raw = Try {
        val options = ConfigParseOptions.defaults().setAllowMissing(false)
        val application = path match {
          case Some(value) => ConfigFactory.parseFile(value.toFile, options)
          case None =>
            properties
              .get("config.resource")
              .fold(ConfigFactory.parseResources("application.conf"))(name =>
                ConfigFactory.parseResources(name, options)
              )
        }
        ConfigFactory
          .parseMap(properties.filter(_._1.startsWith("polyglot-im.")).asJava)
          .withFallback(application)
          .withFallback(ConfigFactory.defaultReference())
          .resolve()
      }.toEither.left.map(_ => "cannot read or resolve HOCON configuration; check file, syntax and substitutions")
      raw.flatMap(config => read(config, path.fold(Path.of("").toAbsolutePath)(_.toAbsolutePath.getParent)))
    }
  }

  private def read(config: Config, directory: Path): Either[String, AppConfig] = {
    val parsed = Try(ConfigSource.fromConfig(config).at("polyglot-im").load[AppSettings]).toEither.left
      .map(_ => "invalid configuration fields: polyglot-im")
      .flatMap(_.left.map { failures =>
        val paths = failures.toList
          .map {
            case ConvertFailure(KeyNotFound(key, _), _, path) => path + "." + key
            case ConvertFailure(_, _, path)                   => path
            case _                                            => "polyglot-im"
          }
          .distinct
          .mkString(", ")
        "invalid configuration fields: " + paths
      })
    parsed.flatMap { value =>
      val a = value.auth
      val d = value.database

      /** 将参数解析异常转换为只包含字段路径的脱敏配置错误。 */
      def checked[A](field: String)(run: => A): Either[String, A] =
        Try(run).toEither.left.map(_ => "invalid configuration field: " + field)

      /** 读取启用功能所必需的非空配置字段，缺失时返回字段错误。 */
      def required(name: String, value: Option[String]) =
        value.filter(_.trim.nonEmpty).toRight("missing configuration field: polyglot-im.apns." + name)
      for {
        host <- Host.fromString(value.server.host).toRight("invalid configuration field: polyglot-im.server.host")
        port <- Port.fromInt(value.server.port).toRight("invalid configuration field: polyglot-im.server.port")
        database <- checked("polyglot-im.database")(
          DatabaseConfig(
            d.url,
            d.user,
            d.password,
            d.poolSize,
            d.connectionTimeout.toMillis,
            d.statementTimeout.toMillis,
            d.lockTimeout.toMillis
          )
        )
        _ <- checked("polyglot-im.auth") {
          Protocol.bounded(a.appId, "app-id")
          require(a.issuer.trim.nonEmpty)
          val secrets = List(a.userJwtSecret, a.serviceJwtSecret, a.cursorSecret)
          require(secrets.forall(s => s.getBytes(UTF_8).length >= 32 && !s.startsWith("replace-")))
          require(secrets.distinct.size == 3)
        }
        introspection <- a.introspectionUrl.traverse { url =>
          checked("polyglot-im.auth.introspection-url") {
            val uri = URI.create(url)
            require(uri.getHost != null && uri.getUserInfo == null && uri.getFragment == null)
            require(
              uri.getScheme == "https" || (uri.getScheme == "http" && Set("127.0.0.1", "localhost", "::1")(uri.getHost))
            )
            uri
          }
        }
        apns <-
          if (!value.apns.enabled) Right(None)
          else
            (for {
              team  <- required("team-id", value.apns.teamId)
              key   <- required("key-id", value.apns.keyId)
              topic <- required("topic", value.apns.topic)
              file  <- required("key-file", value.apns.keyFile)
              path  <- checked("polyglot-im.apns.key-file")(directory.resolve(file).normalize())
            } yield Some(ApnsConfig(team, key, topic, path, value.apns.sandbox)))
        _ <- checked("polyglot-im.server.max-in-flight")(
          require(value.server.maxInFlight > 0 && value.server.maxInFlight <= 65536)
        )
        limits <- checked("polyglot-im.rate-limits")(
          RateLimits(value.rateLimits.userPerMinute, value.rateLimits.servicePerMinute)
        )
      } yield AppConfig(
        ServerConfig(host, port),
        database,
        a,
        introspection,
        apns,
        limits,
        value.server.maxInFlight,
        value.push
      )
    }
  }
}
