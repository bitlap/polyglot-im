# Scala SDK 集成

## 当前安装方式

SDK 尚未发布到 Maven。仓库内运行 `just test-scala` 和 `just build-scala`。同一 sbt 构建中的模块通过 `.dependsOn(sdk)` 引用；外部工程可先从源码构建本地产物，但当前 publish/skip=true，未提供 publishLocal 自动流程。

环境为 JDK 17+、Scala 3.6.3。当前只维护 Scala 3 构建；Scala 2.13、Scala.js、Scala Native 未验证且不在当前支持声明中。

## 当前消息编解码

```scala
import polyglot.im.Message
import io.circe.parser.decode
import io.circe.syntax.*

val json = """{
  "schema_version": 1,
  "message_id": "msg_001",
  "conversation_id": "conv_001",
  "conversation_seq": "1",
  "type": "text",
  "payload": {"text": "你好"},
  "created_at": "2026-11-11T08:00:00Z"
}"""
val message: Either[io.circe.Error, Message] = decode[Message](json)
val encoded: Either[io.circe.Error, String] = message.map(_.asJson.noSpaces)
```

当前 decoder 会将无效身份、序号、时间和主版本转换为 Left。payload 使用 Circe Json，未知类型可以由宿主安全回退。消息对象构造时也会验证约束；直接构造非法对象会抛出参数异常。

## 服务端集成（规划）

后续拆分用户客户端与服务端发送客户端。业务后端完成事务时写本地 Outbox，再以服务凭证提交 `task.completed`、评论等事件；不要在业务事务提交后无重试地调用一次消息 API。

目标异步发送输入为 conversationId、clientMessageId、messageType、payload；返回正式 messageId、seq 和 accepted 状态。客户端不能指定普通用户以外的发送者，服务端 SDK 仅按凭证作用域代业务服务发送。

## 机器人与消费（规划）

JVM 机器人通过受限身份订阅事件，业务处理结果和消费位置使用自身持久化策略，避免回调重放重复执行外部副作用。Future 或效果系统适配层不能改变至少一次投递语义。
