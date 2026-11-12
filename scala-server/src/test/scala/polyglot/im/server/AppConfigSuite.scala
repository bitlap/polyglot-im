package polyglot.im.server

import polyglot.im.server.config.AppConfig

import java.nio.file.{Files, Path}
import scala.concurrent.duration.*
import munit.FunSuite

class AppConfigSuite extends FunSuite {
  private val base = s"""
    polyglot-im {
      database { url="jdbc:postgresql://localhost/test", user="test", password="test-db-password" }
      auth { app-id="config-test", user-jwt-secret="${"u" * 32}", service-jwt-secret="${"s" * 32}", cursor-secret="${"c" * 32}" }
    }
    """
  private def files[A](run: Path => A): A = {
    val directory = Files.createTempDirectory("hocon-test")
    try run(directory)
    finally {
      val paths = Files.walk(directory)
      try paths.sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete)
      finally paths.close()
    }
  }
  private def write(directory: Path, name: String, value: String): Path =
    Files.writeString(directory.resolve(name), value)

  test("直接读取 HOCON、include、替换表达式、时长与默认值，配置打印脱敏") {
    files { dir =>
      write(dir, "common.conf", base)
      val path = write(
        dir,
        "app config.conf",
        """include "common.conf"
        polyglot-im.server { host="127.0.0.2", port=8123 }
        polyglot-im.database.connection-timeout=3 seconds
        polyglot-im.auth.issuer=${polyglot-im.auth.app-id}
        """
      )
      val config = AppConfig.load(Some(path), Map.empty, Map.empty).toOption.get
      assertEquals(config.server.port.value, 8123)
      assertEquals(config.database.maxPoolSize, 8)
      assertEquals(config.database.connectionTimeoutMs, 3000L)
      assertEquals(config.auth.issuer, "config-test")
      assertEquals(config.apns, None)
      assert(!config.toString.contains("test-db-password"))
      assert(!config.toString.contains("u" * 32))
    }
  }
  test("JVM 属性覆盖 HOCON 文件，旧环境变量不再覆盖字段") {
    files { dir =>
      val path = write(dir, "app.conf", base + "\npolyglot-im.database.connection-timeout=3 seconds")
      val config = AppConfig
        .load(
          Some(path),
          Map(
            "POLYGLOT_IM_PORT"                           -> "8123",
            "POLYGLOT_IM_DATABASE_POOL_SIZE"             -> "16",
            "POLYGLOT_IM_DATABASE_CONNECTION_TIMEOUT_MS" -> "2500"
          ),
          Map("polyglot-im.server.port" -> "8124")
        )
        .toOption
        .get
      assertEquals(config.server.port.value, 8124)
      assertEquals(config.database.maxPoolSize, 8)
      assertEquals(config.database.connectionTimeoutMs, 3000L)
    }
  }
  test("配置文件选择顺序为命令行、config.file、POLYGLOT_IM_CONFIG") {
    files { dir =>
      val first  = write(dir, "first.conf", base)
      val second = write(dir, "second.conf", base.replace("config-test", "second"))
      val env    = Map("POLYGLOT_IM_CONFIG" -> second.toString)
      val props  = Map("config.file" -> first.toString)
      assertEquals(AppConfig.load(None, env, props).toOption.get.auth.appId, "config-test")
      assertEquals(AppConfig.load(Some(second), env, props).toOption.get.auth.appId, "second")
      assertEquals(AppConfig.load(None, env, Map.empty).toOption.get.auth.appId, "second")
    }
  }
  test("默认 classpath application.conf 使用 JVM 配置及 reference 默认值") {
    val properties = Map(
      "polyglot-im.database.url"            -> "jdbc:postgresql://localhost/test",
      "polyglot-im.database.user"           -> "test",
      "polyglot-im.auth.app-id"             -> "config-test",
      "polyglot-im.auth.user-jwt-secret"    -> ("u" * 32),
      "polyglot-im.auth.service-jwt-secret" -> ("s" * 32),
      "polyglot-im.auth.cursor-secret"      -> ("c" * 32)
    )
    val config = AppConfig.load(None, Map.empty, properties).toOption.get
    assertEquals(config.auth.appId, "config-test")
    assertEquals(config.server.port.value, 8090)
  }
  test("外部业务会话模式无需用户 JWT 密钥，服务和游标仍要求独立密钥") {
    val properties = Map(
      "polyglot-im.database.url"            -> "jdbc:postgresql://localhost/test",
      "polyglot-im.database.user"           -> "test",
      "polyglot-im.auth.app-id"             -> "snapflow",
      "polyglot-im.auth.introspection-url"  -> "https://business.example/api/v1/im/identity",
      "polyglot-im.auth.service-jwt-secret" -> ("s" * 32),
      "polyglot-im.auth.cursor-secret"      -> ("c" * 32)
    )
    val config = AppConfig.load(None, Map.empty, properties).toOption.get
    assertEquals(config.auth.userJwtSecret, "")
    assert(config.introspection.nonEmpty)
    assert(AppConfig.load(None, Map.empty, properties.updated("polyglot-im.auth.cursor-secret", "s" * 32)).isLeft)
    assert(AppConfig.load(None, Map.empty, properties - "polyglot-im.auth.introspection-url").isLeft)
  }
  test("非法字段、未知字段、弱密钥和不安全校验端点在连接数据库之前拒绝，错误不含值") {
    files { dir =>
      val path = write(dir, "app.conf", base)
      val cases = List(
        "server.max-in-flight"        -> "0",
        "database.statement-timeout"  -> "0 seconds",
        "database.lock-timeout"       -> "20 seconds",
        "push.parallelism"            -> "257",
        "push.lease-duration"         -> "10 seconds",
        "push.job-timeout"            -> "0 seconds",
        "push.poll-interval"          -> "0 seconds",
        "server.port"                 -> "sensitive-invalid-port",
        "database.url"                -> "jdbc:sqlite:sensitive",
        "database.pool-size"          -> "0",
        "database.connection-timeout" -> "249 ms",
        "database.connection-timeout" -> "infinite",
        "auth.user-jwt-secret"        -> "sensitive-short",
        "auth.cursor-secret"          -> ("u" * 32),
        "auth.user-jwt-secret"        -> "replace-with-random-secret-at-least-32-bytes",
        "auth.introspection-url"      -> "http://example.com/sensitive",
        "auth.introspection-url"      -> "https://secret@example.com/sensitive",
        "rate-limits.user-per-minute" -> "0",
        "database.pool-szie"          -> "sensitive-typo"
      )
      cases.foreach { case (key, value) =>
        val result = AppConfig.load(Some(path), Map.empty, Map("polyglot-im." + key -> value))
        assert(result.isLeft, key)
        assert(!result.swap.toOption.get.contains(value), key)
      }
    }
  }
  test("APNs 按开关验证必填参数，私钥路径相对配置文件解析") {
    files { dir =>
      val path    = write(dir, "app.conf", base)
      val enabled = Map("polyglot-im.apns.enabled" -> "true")
      assert(AppConfig.load(Some(path), Map.empty, enabled).isLeft)
      val props = enabled ++ Map(
        "team-id"  -> "team",
        "key-id"   -> "key",
        "topic"    -> "app",
        "key-file" -> "keys/test.p8",
        "sandbox"  -> "true"
      ).map { case (k, v) => "polyglot-im.apns." + k -> v }
      val apns = AppConfig.load(Some(path), Map.empty, props).toOption.get.apns.get
      assertEquals(apns.keyFile, dir.resolve("keys/test.p8"))
      assert(apns.sandbox)
    }
  }
  test("缺失文件、语法错误和未解析替换表达式返回脱敏错误，命令行支持空格路径") {
    files { dir =>
      assert(AppConfig.load(Some(dir.resolve("missing.conf")), Map.empty, Map.empty).isLeft)
      val malformed = write(dir, "bad.conf", "secret-sensitive {")
      val error     = AppConfig.load(Some(malformed), Map.empty, Map.empty).swap.toOption.get
      assert(!error.contains("secret-sensitive"))
      val unresolved = write(dir, "unresolved.conf", base + "\npolyglot-im.auth.issuer=${missing.value}")
      assert(AppConfig.load(Some(unresolved), Map.empty, Map.empty).isLeft)
      assert(AppConfig.load(Some(write(dir, "empty.conf", "")), Map.empty, Map.empty).isLeft)
      assertEquals(AppConfig.location(List("--config", "app config.conf")), Right(Some(Path.of("app config.conf"))))
      assert(AppConfig.location(List("--unknown")).isLeft)
    }
  }
}
