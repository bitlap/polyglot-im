package polyglot.im.server

import io.circe.Json
import munit.FunSuite

class ProtocolSuite extends FunSuite {
  private def command(kind: String, payload: Json) = Json.obj(
    "recipient_id"      -> Json.fromString("alice"),
    "client_message_id" -> Json.fromString("stable-event"),
    "type"              -> Json.fromString(kind),
    "payload"           -> payload
  )
  test("各通知类型必填资源身份，未知类型和危险跳转被拒绝") {
    val required = List(
      "task.completed"   -> List("task_id"),
      "task.failed"      -> List("task_id"),
      "comment.created"  -> List("item_id", "comment_id"),
      "comment.replied"  -> List("item_id", "comment_id"),
      "like.created"     -> List("item_id"),
      "favorite.created" -> List("item_id")
    )
    required.foreach { case (kind, fields) =>
      intercept[ApiError](Protocol.notification(command(kind, Json.obj())))
      val payload = Json.obj(fields.map(_ -> Json.fromString("resource"))*)
      assertEquals(Protocol.notification(command(kind, payload)).kind, kind)
    }
    assertEquals(Protocol.notification(command("custom.photos.ready", Json.obj())).kind, "custom.photos.ready")
    intercept[ApiError](Protocol.notification(command("unsupported", Json.obj())))
    intercept[ApiError](
      Protocol.notification(
        command("system.notice", Json.obj("action" -> Json.obj("type" -> Json.fromString("javascript"))))
      )
    )
  }
  test("Unicode 码点限制和 UTF-8 字节限制分别验证") {
    val emoji = "🎉" * 2048
    assertEquals(
      Protocol
        .notification(command("system.notice", Json.obj("title" -> Json.fromString(emoji))))
        .payload
        .hcursor
        .get[String]("title")
        .toOption,
      Some(emoji)
    )
    intercept[ApiError](
      Protocol.notification(command("system.notice", Json.obj("title" -> Json.fromString(emoji + "x"))))
    )
    val oversized = command("custom.test.bytes", Json.obj("opaque" -> Json.fromString("中" * 6000)))
    assertEquals(intercept[ApiError](Protocol.notification(oversized)).status, 413)
    intercept[ApiError](Protocol.notification(command("system.notice", Json.obj("text" -> Json.fromInt(42)))))
  }
  test("非法序号、控制字符和无效限流配置不被接受") {
    List("-1", "01", "+1", "1.5", "9223372036854775808").foreach(v => intercept[ApiError](Protocol.number(v)))
    assertEquals(Protocol.number("0"), 0L)
    intercept[ApiError](Protocol.bounded("user\nname", "user_id"))
    assertEquals(RateLimits(), RateLimits(600, 120))
    intercept[IllegalArgumentException](RateLimits(0, 120))
    intercept[IllegalArgumentException](RateLimits(600, -1))
  }
}
