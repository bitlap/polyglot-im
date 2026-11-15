/**
 * 客户端缓存端口；由平台适配器或宿主实现，不承担业务 Outbox。
 * @typedef {Object} ClientCache
 * @property {(key: string) => Object|Promise<Object>} load 读取账号缓存，不存在时返回空缓存。
 * @property {(key: string, cache: Object) => void|Promise<void>} save 原子保存整个账号缓存，配额或事务失败必须抛错。
 * @property {(key: string) => void|Promise<void>} [clear] 删除当前账号缓存，clearCache 时必需。
 */
export {};
