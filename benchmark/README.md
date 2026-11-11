# 并发与延迟基准

模块版本随根目录 VERSION（当前 0.1.0）。本模块运行真实 http4s 服务、JWT 鉴权、HikariCP 和 PostgreSQL；不以单元测试时长或内存 mock 推断数据库性能。

```sh
just benchmark
just benchmark --requests 1000 --warmup 100 --repeats 3 --concurrency 1 8 32 128
just benchmark-smoke
just test-benchmark
```

运行需要项目工具链与 `just setup`。临时数据库、随机凭证只用于本机 loopback，退出释放。原始 JSON 结果仅包含参数、环境、指标及样本，不包含凭证。长压测不加入普通单元测试；CI 只做短时冒烟，避免把共享 runner 波动当作性能回归。

## 测试口径

- HTTP 使用闭环并发 worker；级别 1 / 8 / 32 / 128。成功吞吐为成功请求数除以完整测量时间，错误请求计入总请求及错误率，关闭客户端自动重试。
- 延迟由压测机 perf_counter_ns 测量，从请求构造/发送开始到完整响应读取、JSON 验证完成，含连接池等待；排除前置数据准备和预热。不包含 SDK 的磁盘缓存/UI 开销。
- 分位数使用 nearest-rank，跨重复实验合并原始样本，吞吐按成功数/总耗时加权；不平均各次 p99。
- 写入区分 256 个用户分散写入与单用户热点竞争；历史每账户预置 20 条，分页读取 20 条；空增量不与“读取有消息”混算。
- WebSocket 每连接对应独立用户，先建立连接/预热，再持续发消息；延迟从每条 POST 开始到对应 message.created 帧被接收，包含持久化及服务端 500ms 轮询等待。吞吐含连接建立及末尾排空，单列结果。
- 压测专用实例将用户/服务限流提高到 Int.MaxValue；正式服务仍为每分钟 user=600、service=120。每个用户预注册一个合成 iOS token，写入包含一条推送 Outbox 的事务成本；APNs Worker 关闭，未测 Apple 网络延迟。
- 明确启用 PostgreSQL fsync、synchronous_commit、full_page_writes，连接池上限 8，JVM 4 个可见处理器、堆 512MiB–2GiB。实际参数写入结果。

闭环测试会在响应变慢时降低发压速率，未做 coordinated omission 修正；不能当作固定到达率的 SLO 或最大容量证明。方法边界参考 [wrk2](https://github.com/giltene/wrk2) 和 [Python 单调计时器](https://docs.python.org/3/library/time.html#time.perf_counter_ns)。

## 实测结果

实现及单元测试完成后在当前机器运行，结果由原始 JSON 生成并更新到此节。这里不预填未经执行的吞吐或延迟数值。
