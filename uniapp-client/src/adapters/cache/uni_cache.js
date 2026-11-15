import { emptyCache } from "../../domain/cache.js";

/** uni-app 本地缓存；配额失败向上传递，不能用内存替代持久化。 */
export class UniCache {
  /** 读取当前账号隔离键，尚无记录时返回空缓存。 */
  load(key) { return uni.getStorageSync(key) || emptyCache(); }
  /** 原子替换整个账号缓存；调用方必须等待保存成功才推进游标和 ack。 */
  save(key, value) { uni.setStorageSync(key, value); }
  /** 清除当前账号缓存，不删除服务器消息。 */
  clear(key) { uni.removeStorageSync(key); }
}
/** 默认平台缓存实例；无全局数据库连接。 */
export const uniCache = new UniCache();
