/**
 * 宿主适配器契约，仅用于类型说明，不创建网络、存储或平台资源。
 * @typedef {Object} Storage
 * @property {(key: string) => Object|Promise<Object>} load 读取账号缓存。
 * @property {(key: string, cache: Object) => void|Promise<void>} save 原子保存缓存，失败须抛错。
 * @typedef {(options: Object) => Promise<{statusCode: number, data: Object}>} Transport
 * @typedef {() => Promise<string>} TokenProvider 每次读取并验证当前业务身份。
 */
export {}
