/**
 * 宿主适配器契约，仅描述端口，不创建平台资源。所有适配器不得记录凭证或 ticket。
 * @typedef {Object} Storage
 * @property {(key: string) => Object|Promise<Object>} load 读取账号缓存，不存在时返回空缓存。
 * @property {(key: string, cache: Object) => void|Promise<void>} save 原子保存整个账号缓存，配额或事务失败必须抛错。
 * @property {(key: string) => void|Promise<void>} [clear] 删除当前账号缓存，clearCache 时必需。
 * @typedef {(options: Object) => Promise<{statusCode: number, data: Object|string, header?: Object}>} Transport
 * options 包含 url/method/data/header/timeout/signal；执行超时并监听 signal 的 abort 事件；重定向策略由具体平台或传输实现负责。
 * 传输默认为借入，只有 ownsTransport:true 时客户端才调用其可选 close 方法。
 * @typedef {(options: {url: string, onOpen: Function, onMessage: Function, onClose: Function, onError: Function}) => {send: Function, close: Function}} SocketConnector
 * 每次连接独立回调；onMessage 传 JSON 文本，send 可返回 Promise，close 必须幂等释放当前连接。
 * @typedef {() => Promise<string>} TokenProvider 每次读取并验证当前业务身份。
 * @typedef {() => Promise<void>} RefreshToken 同一身份内刷新，拒绝跨账号刷新结果。
 * @typedef {{encode: Function, decode: Function}} WireCodec 编码/解码 JSON，不得改变字符串序号。
 */
export {};
