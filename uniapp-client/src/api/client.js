import { NotificationClient } from '../service/notification_client.js'
import { uniTransport, uniStorage } from '../adapters/uni.js'

/** 默认 uni-app 组合根；宿主可显式替换传输、存储和凭证实现。 */
export class ImClient extends NotificationClient {
    constructor(options) {
        super({ transport: uniTransport, storage: uniStorage, ...options })
    }
}
