# Python SDK 集成

## 当前安装

Python 3.11+。仓库内执行 `uv sync --project python --locked`。从其他工程安装本地源码：

```sh
uv add /absolute/path/to/polyglot-im/python
```

包分发名为 polyglot-im-python，导入名为 polyglot_im。当前未上传 PyPI，不要使用未经验证的同名公共包作为本项目 SDK。

## 当前编解码

```python
from polyglot_im import Message

message = Message.from_dict({
    "schema_version": 1,
    "message_id": "msg_001",
    "conversation_id": "conv_001",
    "conversation_seq": "1",
    "type": "text",
    "payload": {"text": "你好"},
    "created_at": "2026-11-11T08:00:00Z",
})
assert message.to_dict()["payload"]["text"] == "你好"
```

缺少必需字段产生 KeyError；无效主版本、身份、序号、payload 和时间产生 ValueError。未知信封字段被忽略；payload 保留业务结构。dataclass frozen 只限制属性重新赋值，不保证嵌套 payload 深度不可变，调用方不能在共享后随意修改。

测试运行 `just test-python`；构建 `just build-python`，得到 dist/python 下的 wheel 与 sdist。

## AI 任务与服务端发送（规划）

Python 的主要使用场景是任务完成通知、机器人与自动化服务。目标 SDK 采用 async/await；一次性发送不要求建立 WS。服务凭证从外部机密配置注入，禁止写入代码或发送给 Flutter。

任务 ID 与通知用途共同组成稳定发送意图，例如 `task:<id>:completed:<result_version>`。提交超时后复用相同 client_message_id。任务状态数据库与消息数据库不在同一事务时，在任务系统写本地 Outbox，由可重试 Worker 调用 IM。

机器人订阅使用异步迭代器；消费消息有至少一次语义，调用外部支付、发邮件或创建工单等副作用必须额外幂等。SDK 重连不意味着业务副作用自动回滚。

## 当前没有的 API

connect、send_message、watch_messages、mark_read、sync、Webhook 验签与文件上传都尚未实现。设计契约见[集成总览](README.md)及[协议文档](../protocol.md)。
