import { emptyCache } from '../domain/cache.js'

/** 默认 uni.request 传输；业务凭证只进入 Authorization，不进入 URL 或日志。 */
export const uniTransport = options => new Promise((resolve, reject) => uni.request({
    ...options, success: resolve, fail: () => reject(new Error('消息网络连接失败')),
}))
/** 默认账号隔离持久化；保存成功后客户端才推进游标及确认收取。 */
export const uniStorage = {
    load: key => uni.getStorageSync(key) || emptyCache(),
    save: (key, value) => uni.setStorageSync(key, value),
}
