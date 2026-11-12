import { validateMessage } from "../domain/cache.js";

/** 可信服务端通知发送端口；api 必须使用服务凭证，禁止向小程序分发服务凭证。 */
export class NotificationSender {
  constructor(api) {
    this.api = api;
  }
  /** 幂等发送通知；跨重试复用 clientMessageId，模板和跳转由业务服务端冻结。 */
  async send({ recipientId, clientMessageId, type, payload }) {
    if (!recipientId || !clientMessageId || !type)
      throw new Error("recipientId, stable clientMessageId and type required");
    return validateMessage(
      await this.api.request("POST", "/notifications", {
        recipient_id: recipientId,
        client_message_id: clientMessageId,
        type,
        payload,
      }),
    );
  }
}
