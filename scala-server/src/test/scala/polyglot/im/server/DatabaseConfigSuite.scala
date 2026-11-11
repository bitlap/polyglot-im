package polyglot.im.server

import polyglot.im.server.config.DatabaseConfig
import munit.FunSuite

class DatabaseConfigSuite extends FunSuite {
  test("PostgreSQL 连接配置默认值与池覆盖") {
    val defaults = DatabaseConfig("jdbc:postgresql://localhost/im?sslmode=require", "im", "")
    assertEquals(defaults.maxPoolSize, 8)
    assertEquals(defaults.connectionTimeoutMs, 10000L)
    val custom = DatabaseConfig(defaults.url, "im", "test-only", 16, 30000L)
    assertEquals(custom.maxPoolSize, 16)
    assertEquals(custom.connectionTimeoutMs, 30000L)
    assertEquals(custom.password, "test-only")
    assert(!custom.toString.contains("test-only"))
  }
  test("其他数据库和非法池参数在连接前拒绝且不回显凭证") {
    val invalid = List[() => DatabaseConfig](
      () => DatabaseConfig("jdbc:sqlite:secret-path", "im", ""),
      () => DatabaseConfig("jdbc:postgresql://localhost/im", " ", ""),
      () => DatabaseConfig("jdbc:postgresql://localhost/im", "im", "", 0),
      () => DatabaseConfig("jdbc:postgresql://localhost/im", "im", "", 8, 249L),
      () => DatabaseConfig("jdbc:postgresql://localhost/im", "im", "", 8, -1L)
    )
    invalid.foreach { create =>
      val error = intercept[IllegalArgumentException](create())
      assert(!error.getMessage.contains("secret-path"))
    }
  }
}
