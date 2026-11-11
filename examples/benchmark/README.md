# Polyglot IM Benchmark 示例

在仓库根目录运行：

```sh
just benchmark-smoke
just benchmark --requests 1000 --repeats 3 --concurrency 1 8 32 128
```

压测会自动启动真实 PostgreSQL / HTTP / WebSocket 服务，退出后清理临时数据库。参数、原始样本与实测结果见[benchmark 模块](../../benchmark/README.md)。

[全部示例](../README.md) · [返回项目首页](../../README.md)
