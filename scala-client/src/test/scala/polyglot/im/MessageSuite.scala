package polyglot.im

import java.nio.file.{Files, Path}

import io.circe.parser.{decode, parse}
import io.circe.syntax.*

class MessageSuite extends munit.FunSuite {
  private val fixture = Files.readString(Path.of(sys.props("polyglot.im.root"), "protocol", "fixtures", "message.json"))

  test("单条已读、删除墓碑与迟到历史共享契约") {
    val data = parse(
      Files.readString(Path.of(sys.props("polyglot.im.root"), "protocol/fixtures/notification-actions.json"))
    ).toOption.get
    def page(name: String) = data.hcursor.downField(name).focus.get
    var state = polyglot.im.domain.LocalCache.applyPage(polyglot.im.domain.LocalCache.empty, page("initial"))
    state = polyglot.im.domain.LocalCache.applyPage(state, page("read"))
    assertEquals(state.hcursor.get[String]("read_seq").toOption, Some("0"))
    assertEquals(state.hcursor.get[String]("unread_count").toOption, Some("2"))
    state = polyglot.im.domain.LocalCache.applyPage(state, page("delete"))
    val stale = io.circe.Json.obj("messages" -> page("initial").hcursor.downField("messages").focus.get)
    assertEquals(
      polyglot.im.domain.LocalCache
        .applyPage(state, stale.deepMerge(io.circe.Json.obj("history_position" -> io.circe.Json.fromString("3")))),
      state
    )
    state = polyglot.im.domain.LocalCache.applyPage(state, stale)
    assertEquals(
      state.hcursor.downField("messages").downField("notice-action").get[Boolean]("is_deleted").toOption,
      Some(true)
    )
    assertEquals(state.hcursor.get[String]("unread_count").toOption, Some("1"))
  }

  test("共享消息样例往返编码且保留超出 JS 安全范围的序号") {
    val message = decode[Message](fixture).toOption.get
    assertEquals(message.conversationSeq, "9007199254740993")
    assertEquals(message.asJson, parse(fixture).toOption.get)
  }

  test("拒绝无效序号并返回解码错误") {
    assert(decode[Message](fixture.replace("9007199254740993", "-1")).isLeft)
  }

  test("保留未知消息类型与 payload") {
    val message = decode[Message](fixture.replace("\"type\": \"text\"", "\"type\": \"custom.future\"")).toOption.get
    assertEquals(message.messageType, "custom.future")
    assert(message.payload.hcursor.get[String]("text").isRight)
  }
  test("单聊 Unicode 契约与历史页不回退会话版本") {
    val data = parse(
      Files.readString(Path.of(sys.props("polyglot.im.root"), "protocol", "fixtures", "direct-message.json"))
    ).toOption.get
    assertEquals(decode[Message](data.noSpaces).toOption.get.asJson, data)
    val patch = io.circe.Json.obj(
      "conversation_id" -> data.hcursor.downField("conversation_id").focus.get,
      "revision"        -> io.circe.Json.fromString("3"),
      "read_seq"        -> io.circe.Json.fromString("2"),
      "messages"        -> io.circe.Json.arr(data)
    )
    val page  = io.circe.Json.obj("conversations" -> io.circe.Json.arr(patch))
    val state = LocalCache.applyPage(LocalCache.empty, page)
    val stale = page.mapObject(
      _.add(
        "conversations",
        io.circe.Json.arr(
          patch.deepMerge(
            io.circe.Json.obj(
              "revision" -> io.circe.Json.fromString("1"),
              "read_seq" -> io.circe.Json.fromString("0"),
              "messages" -> io.circe.Json.arr()
            )
          )
        )
      )
    )
    val saved = LocalCache.applyPage(state, stale).hcursor.downField("conversations").downField("direct_contract")
    assertEquals(saved.get[String]("read_seq").toOption, Some("2"))
    assertEquals(saved.downField("messages").focus.get.asObject.get.size, 1)
    assertEquals(state.hcursor.downField("messages").focus.get.asObject.get.size, 0)
  }
  test("作者审核动作共享契约") {
    val raw = Files.readString(Path.of(sys.props("polyglot.im.root"), "protocol", "fixtures", "review-message.json"))
    assertEquals(decode[Message](raw).toOption.get.asJson, parse(raw).toOption.get)
  }
}
