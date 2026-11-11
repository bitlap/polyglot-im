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
}
