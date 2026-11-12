# uni-app 通知客户端

通过本地 npm 包 `file:../polyglot-im/uniapp-client` 接入，ES module，不依赖 Node 运行时。默认使用 `uni.request` 和账号隔离的 `uni` 持久化；传输、存储、凭证提供器均可注入。仅实现通知，不提供单聊 UI 或微信订阅消息。

`ImClient` 必填 baseUrl、appId、userId、environment、deviceId、tokenProvider。environment 使用业务 API 地址。tokenProvider 返回现有不透明 Bearer，必须检查当前账号与绑定 userId 一致；登录失效由宿主刷新并重新绑定，不把凭证写入缓存。initialize 建立会话并补拉；前台 startPolling，后台 stopPolling；主动退出 close({logout:true}) 成功后才撤销业务会话。被动失效使用 close()。

每页缓存落盘后才 ack；缓存最多保留 500 条通知，历史由 history 分页获取。markRead 仅用于已向用户展示的序号。序号全程保留十进制字符串。默认 20 秒前台轮询，失败指数退避至 120 秒，重复同步合并为一次请求链。微信小程序需在后台配置 IM HTTPS request 合法域名。iOS APNs 属于原生 App，小程序站内消息不产生 iOS 原生系统通知。

运行 `npm test --prefix uniapp-client`。

`just build-uniapp` 在 dist/uniapp-client 生成包含根 LICENSE/NOTICE 的本地 tgz，未上传 registry；宿主也可直接使用上述 file 依赖。401 时可选 refreshToken 回调最多刷新一次，宿主需拒绝跨账号重放。
