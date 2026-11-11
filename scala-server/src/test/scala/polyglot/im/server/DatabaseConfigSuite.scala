package polyglot.im.server

import munit.FunSuite

class DatabaseConfigSuite extends FunSuite {
  private val base = Map(
    "POLYGLOT_IM_DATABASE_URL"  -> "jdbc:postgresql://localhost/im?sslmode=require",
    "POLYGLOT_IM_DATABASE_USER" -> "im"
  )
  test("PostgreSQL 连接配置默认值与池覆盖") {
    val defaults = DatabaseConfig.fromEnvironment(base).toOption.get
    assertEquals(defaults.maxPoolSize, 8)
    assertEquals(defaults.connectionTimeoutMs, 10000L)
    assertEquals(defaults.password, "")
    val custom = DatabaseConfig
      .fromEnvironment(
        base ++ Map(
          "POLYGLOT_IM_DATABASE_POOL_SIZE"             -> "16",
          "POLYGLOT_IM_DATABASE_CONNECTION_TIMEOUT_MS" -> "30000",
          "POLYGLOT_IM_DATABASE_PASSWORD"              -> "test-only"
        )
      )
      .toOption
      .get
    assertEquals(custom.maxPoolSize, 16)
    assertEquals(custom.connectionTimeoutMs, 30000L)
    assertEquals(custom.password, "test-only")
  }
  test("缺失、其他数据库和非法池参数在连接前拒绝且不回显凭证") {
    val invalid = List(
      Map.empty[String, String],
      base.updated("POLYGLOT_IM_DATABASE_URL", "jdbc:sqlite:secret-path"),
      base.updated("POLYGLOT_IM_DATABASE_USER", " "),
      base.updated("POLYGLOT_IM_DATABASE_POOL_SIZE", "0"),
      base.updated("POLYGLOT_IM_DATABASE_POOL_SIZE", "abc"),
      base.updated("POLYGLOT_IM_DATABASE_CONNECTION_TIMEOUT_MS", "249"),
      base.updated("POLYGLOT_IM_DATABASE_CONNECTION_TIMEOUT_MS", "-1")
    )
    invalid.foreach { env =>
      val result = DatabaseConfig.fromEnvironment(env)
      assert(result.isLeft)
      assert(!result.left.toOption.get.contains("secret-path"))
    }
    intercept[IllegalArgumentException](DatabaseConfig("jdbc:sqlite:db", "im", ""))
  }
}
