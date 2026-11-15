import { NotificationClient } from "../service/notification_client.js";
import {
  uniTransport,
  uniCache,
  uniSocketConnector,
} from "../adapters/uni.js";

/** 默认 uni-app 组合根；宿主可显式替换传输、存储和凭证实现。 */
export class ImClient extends NotificationClient {
  /** 注入账号缓存；cachePersistence 与旧 storage 只能提供一个，缺省使用 uniCache。 */
  constructor(options) {
    const { storage, ...current } = options;
    if (storage != null && options.cachePersistence != null)
      throw new Error("provide exactly one cachePersistence");
    super({
      transport: uniTransport,
      socketConnector: uniSocketConnector,
      ...current,
      cachePersistence: options.cachePersistence ?? storage ?? uniCache,
    });
  }
}
