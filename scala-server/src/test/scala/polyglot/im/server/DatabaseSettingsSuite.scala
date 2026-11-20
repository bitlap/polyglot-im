package polyglot.im.server

import polyglot.im.server.config.DatabaseSettings
import munit.FunSuite

class DatabaseSettingsSuite extends FunSuite {
  test("PostgreSQL 连接配置默认值与池覆盖") {
    val defaults = DatabaseSettings("jdbc:postgresql://localhost/im?sslmode=require", "im", "")
    assertEquals(defaults.maxPoolSize, 8)
    assertEquals(defaults.connectionTimeoutMs, 10000L)
    val custom = DatabaseSettings(defaults.url, "im", "test-only", 16, 30000L)
    assertEquals(custom.maxPoolSize, 16)
    assertEquals(custom.connectionTimeoutMs, 30000L)
    assertEquals(custom.password, "test-only")
    assert(!custom.toString.contains("test-only"))
  }
  test("其他数据库和非法池参数在连接前拒绝且不回显凭证") {
    val invalid = List[() => DatabaseSettings](
      () => DatabaseSettings("jdbc:sqlite:secret-path", "im", ""),
      () => DatabaseSettings("jdbc:postgresql://localhost/im", " ", ""),
      () => DatabaseSettings("jdbc:postgresql://localhost/im", "im", "", 0),
      () => DatabaseSettings("jdbc:postgresql://localhost/im", "im", "", 8, 249L),
      () => DatabaseSettings("jdbc:postgresql://localhost/im", "im", "", 8, -1L)
    )
    invalid.foreach { create =>
      val error = intercept[IllegalArgumentException](create())
      assert(!error.getMessage.contains("secret-path"))
    }
  }
}
