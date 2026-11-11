# polyglot-im Python

Python 3.11+ 的 polyglot-im 消息协议基础包。当前提供 Message 编解码；网络客户端与服务端发送接口尚未实现，包尚未发布。

```sh
uv sync --locked
uv run pytest
```

安装、样例和边界见 [Python 集成文档](../docs/sdk/python.md)。从 sdist/wheel 使用者请访问 [GitHub 文档](https://github.com/bitlap/polyglot-im/blob/main/docs/sdk/python.md)。构建与发布统一通过仓库根 justfile。
