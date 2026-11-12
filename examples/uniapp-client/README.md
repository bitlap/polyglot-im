# uni-app 真实服务验收

Node 22 下运行 `node examples/uniapp-client/live.mjs`，默认读取根目录 `.local-demo/config.json`，需先运行 `just demo-server`。示例注入 Node fetch/WebSocket 和原子文件存储端口，验证与小程序共用的 SDK 核心；这些 Node 适配器不会进入发布包。

`just test-integration` 自动启动临时 PostgreSQL/真实 HTTP/WS 服务，构建 npm tgz 并安装到独立临时目录，使用 `POLYGLOT_IM_UNIAPP_ENTRY` 指定安装后入口。覆盖单聊 Unicode、幂等、实时接收、已读回执、历史、会话列表、断线重连、暂停恢复、通知发送与读取、设备偏好、持久化恢复和退出清理。凭证仅来自临时合成环境，不发送 APNs。

小程序默认适配器和宿主生命周期另有单测与 mp-weixin 编译验证；Node 真实网络验收不代替微信真机网络域名、后台挂起或 Apple 推送验收。
