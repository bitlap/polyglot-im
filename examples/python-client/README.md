# Polyglot IM Python Client 示例

先在另一终端从仓库根目录运行 `just demo-server`。

```sh
uv run --project python-client --locked python examples/python-client/live.py
```

在仓库根目录执行；验证 HTTP/WebSocket、SQLite 本地缓存、幂等和已读。可追加配置文件路径，默认读取根目录 .local-demo/config.json。

[pluggable.py](pluggable.py) 展示注入 HTTP、凭证、Socket、原子存储与编解码器；调用方负责资源生命周期。

[子项目功能与集成](../../python-client/README.md) · [全部示例](../README.md) · [返回项目首页](../../README.md)
