package polyglot.im

import java.nio.file.{Files, Path}

import io.circe.parser.{decode, parse}
import io.circe.syntax.*

class MessageSuite extends munit.FunSuite {
  private val fixture = Files.readString(Path.of(sys.props("polyglot.im.root"), "protocol", "fixtures", "message.json"))

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
}
