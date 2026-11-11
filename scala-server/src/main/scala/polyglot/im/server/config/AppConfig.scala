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
import com.typesafe.config.{Config, ConfigFactory, ConfigParseOptions, ConfigValueFactory}
import pureconfig.{ConfigReader, ConfigSource}
import pureconfig.error.{ConvertFailure, KeyNotFound}
import pureconfig.generic.ProductHint
import pureconfig.generic.semiauto.deriveReader

final case class HttpSettings(host: String, port: Int, maxInFlight: Int)
final case class DatabaseSettings(
    url: String,
    user: String,
    password: String,
    poolSize: Int,
    connectionTimeout: FiniteDuration,
    statementTimeout: FiniteDuration,
    lockTimeout: FiniteDuration
) {
  override def toString: String = "DatabaseSettings(<redacted>)"
}
final case class AuthSettings(
    appId: String,
    issuer: String,
    userJwtSecret: String,
    serviceJwtSecret: String,
    cursorSecret: String,
    introspectionUrl: Option[String]
) {
  override def toString: String = "AuthSettings(<redacted>)"
}
final case class ApnsSettings(
    enabled: Boolean,
    teamId: Option[String],
    keyId: Option[String],
    topic: Option[String],
    keyFile: Option[String],
    sandbox: Boolean
)
final case class RateLimitSettings(userPerMinute: Int, servicePerMinute: Int)
final case class AppSettings(
    server: HttpSettings,
    database: DatabaseSettings,
    auth: AuthSettings,
    apns: ApnsSettings,
    rateLimits: RateLimitSettings,
    push: PushWorkerSettings
)
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

object AppConfig {
  private given [A]: ProductHint[A]              = ProductHint[A](allowUnknownKeys = false)
  private given ConfigReader[HttpSettings]       = deriveReader
  private given ConfigReader[DatabaseSettings]   = deriveReader
  private given ConfigReader[AuthSettings]       = deriveReader
  private given ConfigReader[ApnsSettings]       = deriveReader
  private given ConfigReader[RateLimitSettings]  = deriveReader
  private given ConfigReader[PushWorkerSettings] = deriveReader
  private given ConfigReader[AppSettings]        = deriveReader

  private val environmentPaths = Map(
    "POLYGLOT_IM_HOST"                           -> "server.host",
    "POLYGLOT_IM_PORT"                           -> "server.port",
    "POLYGLOT_IM_DATABASE_URL"                   -> "database.url",
    "POLYGLOT_IM_DATABASE_USER"                  -> "database.user",
    "POLYGLOT_IM_DATABASE_PASSWORD"              -> "database.password",
    "POLYGLOT_IM_DATABASE_POOL_SIZE"             -> "database.pool-size",
    "POLYGLOT_IM_DATABASE_CONNECTION_TIMEOUT_MS" -> "database.connection-timeout",
    "POLYGLOT_IM_APP_ID"                         -> "auth.app-id",
    "POLYGLOT_IM_JWT_ISSUER"                     -> "auth.issuer",
    "POLYGLOT_IM_USER_JWT_SECRET"                -> "auth.user-jwt-secret",
    "POLYGLOT_IM_SERVICE_JWT_SECRET"             -> "auth.service-jwt-secret",
    "POLYGLOT_IM_CURSOR_SECRET"                  -> "auth.cursor-secret",
    "POLYGLOT_IM_INTROSPECTION_URL"              -> "auth.introspection-url",
    "POLYGLOT_IM_APNS_ENABLED"                   -> "apns.enabled",
    "POLYGLOT_IM_APNS_TEAM_ID"                   -> "apns.team-id",
    "POLYGLOT_IM_APNS_KEY_ID"                    -> "apns.key-id",
    "POLYGLOT_IM_APNS_TOPIC"                     -> "apns.topic",
    "POLYGLOT_IM_APNS_KEY_FILE"                  -> "apns.key-file",
    "POLYGLOT_IM_APNS_SANDBOX"                   -> "apns.sandbox"
  )
  def location(args: List[String]): Either[String, Option[Path]] = args match {
    case Nil => Right(None)
    case List("--config", path) if path.trim.nonEmpty =>
      Try(Some(Path.of(path))).toEither.left.map(_ => "invalid config file path")
    case _ => Left("usage: run [--config /path/application.conf]")
  }

  /** JVM 属性 > 显式环境覆盖 > 应用配置 > reference 默认值；仅组合根调用。 */
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
        val overrides = environmentPaths.foldLeft(ConfigFactory.empty()) { case (config, (name, key)) =>
          env.get(name).fold(config) { value =>
            val text = if (name.endsWith("_TIMEOUT_MS")) value + " ms" else value
            config.withValue("polyglot-im." + key, ConfigValueFactory.fromAnyRef(text))
          }
        }
        ConfigFactory
          .parseMap(properties.filter(_._1.startsWith("polyglot-im.")).asJava)
          .withFallback(overrides)
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
      def checked[A](field: String)(run: => A): Either[String, A] =
        Try(run).toEither.left.map(_ => "invalid configuration field: " + field)
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
