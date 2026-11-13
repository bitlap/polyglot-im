import {
  compareSequence,
  emptyCache,
  applyPage,
  validateMessage,
} from "../domain/cache.js";
import { HttpApi } from "./http_api.js";

/** 账号同步核心；通过端口提供通知、单聊、实时连接及设备管理。 */
export class NotificationClient extends HttpApi {
  constructor(options) {
    super(options);
    const {
      appId,
      userId,
      environment,
      deviceId,
      storage,
      socketConnector,
      onState = () => {},
      onChange = () => {},
      onError = () => {},
    } = options;
    if (!appId || !userId || !deviceId || !environment)
      throw new Error("Missing account scope");
    Object.assign(this, {
      deviceId,
      storage,
      socketConnector,
      onState,
      onChange,
      onError,
    });
    this.connectionState = "idle";
    this.realtimeGeneration = 0;
    this.key = `im.v1.${encodeURIComponent(JSON.stringify([environment, this.baseUrl, appId, userId]))}`;
    this.cache = emptyCache();
    this.timer = null;
    this.syncing = null;
    this.serial = Promise.resolve();
  }
  /** 发布连接状态；宿主可据此显示重连或登录提示。 */
  state(value) {
    if (!this.closed) {
      this.connectionState = value;
      this.onState(value);
    }
  }
  /** 从隔离缓存恢复，校验服务器身份并建立当前设备会话。 */
  async initialize({ realtime = true } = {}) {
    this.check();
    const loaded = await this.storage.load(this.key);
    this.check();
    this.cache = { ...emptyCache(), ...loaded };
    if (this.closed) return;
    const session = await this.request("POST", "/session", {
      device_id: this.deviceId,
    });
    if (session.app_id !== this.appId || session.user_id !== this.userId) {
      const error = new Error("消息身份不匹配");
      error.code = "IDENTITY_MISMATCH";
      throw error;
    }
    await this.sync();
    if (realtime) this.startRealtime();
    return this.cache;
  }
  /** 串行原子保存整个缓存；落盘失败不推进内存游标或发送 ack。 */
  commit(update, valid = () => true) {
    const task = this.serial
      .catch(() => {})
      .then(async () => {
        if (this.closed) throw new Error("消息账号已关闭");
        if (!valid()) return;
        const next = update(JSON.parse(JSON.stringify(this.cache)));
        await this.storage.save(this.key, next);
        if (this.closed) return;
        this.cache = next;
        this.onChange(this);
      });
    this.serial = task;
    return task;
  }
  /** 原子合并通知、单聊和独立已读水位；历史页不推进增量游标。 */
  async apply(page, reset = false, valid = () => true) {
    await this.commit(
      (current) => (valid() ? applyPage(current, page, reset) : current),
      valid,
    );
  }
  /** 单航班同步，游标失效时恢复快照；仅落盘后确认收取。 */
  sync() {
    this.check();
    if (this.syncing) return this.syncing;
    this.state("syncing");
    this.syncing = (async () => {
      let page;
      do {
        let reset = !this.cache.cursor;
        try {
          page = await this.request(
            "GET",
            reset
              ? "/snapshot"
              : `/sync?cursor=${encodeURIComponent(this.cache.cursor)}`,
          );
        } catch (error) {
          if (error.code !== "RESET_REQUIRED") throw error;
          page = await this.request("GET", "/snapshot");
          reset = true;
        }
        await this.apply(page, reset);
        if (this.cache.cursor)
          await this.request(
            "PUT",
            `/devices/${encodeURIComponent(this.deviceId)}/ack`,
            { cursor: this.cache.cursor },
          );
      } while (page.has_more && !this.closed);
      this.state("online");
      return this.cache;
    })()
      .catch((error) => {
        this.state(
          error.status === 401 || error.code === "IDENTITY_MISMATCH"
            ? "authRequired"
            : "retrying",
        );
        throw error;
      })
      .finally(() => {
        this.syncing = null;
      });
    return this.syncing;
  }
  /** 读取历史，不改变同步游标。 */
  async history(beforeSeq, limit = 50) {
    if (beforeSeq && typeof beforeSeq === "object") {
      limit = beforeSeq.limit ?? 50;
      beforeSeq = beforeSeq.beforeSeq;
    }
    const page = await this.request(
      "GET",
      `/messages?${query({ limit, before_seq: beforeSeq })}`,
    );
    await this.apply({ messages: page.items, ...(page.event_position !== undefined ? {history_position: page.event_position} : {}) });
    return page;
  }
  /** 只把用户已展示的范围设为已读；不会将收取确认当作阅读。 */
  async markRead(sequence = this.cache.latest_seq) {
    await this.request("PUT", "/read", { up_to_seq: sequence });
    await this.sync();
  }
  /** 原子标记 1–100 条指定通知已读，不推进累计阅读水位。 */
  async markMessagesRead(ids) { return this.updateNotifications(ids, false); }
  /** 原子删除 1–100 条当前用户通知，删除状态可靠同步。 */
  async deleteMessages(ids) { return this.updateNotifications(ids, true); }
  /** 校验批次并在服务端提交后补拉，不做本地假删除。 */
  async updateNotifications(ids, remove) {
    if (!Array.isArray(ids) || !ids.length || ids.length > 100 || new Set(ids).size !== ids.length)
      throw new Error("1 to 100 unique message IDs required");
    await this.request(remove ? "POST" : "PUT", remove ? "/notifications/delete" : "/notifications/read", {message_ids: [...ids]});
    if (this.syncing) await this.syncing;
    return this.sync();
  }
  /** 在前台定时补拉，失败指数退避；后台暂停，避免持续耗电。 */
  startPolling(interval = 30000) {
    this.check();
    if (!Number.isFinite(interval) || interval <= 0)
      throw new Error("Invalid polling interval");
    this.stopPolling();
    const generation = this.pollGeneration;
    let delay = interval;
    const poll = async () => {
      try {
        await this.sync();
        delay = interval;
      } catch (error) {
        delay = Math.min(delay * 2, 120000);
        this.onError(error);
      }
      if (!this.closed && this.polling && generation === this.pollGeneration)
        this.timer = setTimeout(poll, delay);
    };
    this.polling = true;
    this.timer = setTimeout(poll, interval);
  }
  /** 暂停轮询，不清除可靠缓存。 */
  stopPolling() {
    this.pollGeneration = (this.pollGeneration || 0) + 1;
    this.polling = false;
    clearTimeout(this.timer);
    this.timer = null;
  }
  /** 关闭实例；默认保留服务端会话以兼容宿主后台恢复，退出应显式 logout。 */
  async close({ logout = false, clearCache = false } = {}) {
    if (this.closed) return;
    this.stopPolling();
    this.pauseRealtime();
    try {
      if (logout)
        await this.request(
          "DELETE",
          `/devices/${encodeURIComponent(this.deviceId)}/session`,
        );
    } finally {
      await super.close();
      await this.serial.catch(() => {});
      if (clearCache) {
        if (!this.storage.clear) throw new Error("Storage clear port required");
        await this.storage.clear(this.key);
      }
    }
  }
  /** 返回按通知序号倒序排列的缓存。 */
  get messages() {
    return Object.values(this.cache.messages).filter(m => !m.is_deleted).sort((a, b) =>
      compareSequence(b.conversation_seq, a.conversation_seq),
    );
  }
  /** 当前通知未读数，字符串避免精度损失。 */
  get unreadCount() {
    return this.cache.unread_count;
  }
  /** 单聊未读数，与通知未读分离。 */
  get chatUnreadCount() {
    return this.cache.chat_unread_count;
  }
  /** 创建或取得与对方用户的唯一单聊。 */
  async createDirect(peerUserId) {
    if (typeof peerUserId !== "string" || !peerUserId)
      throw new Error("peerUserId required");
    const result = await this.request("POST", "/conversations/direct", {
      peer_user_id: peerUserId,
    });
    await this.apply({ conversations: [result] });
    return result;
  }
  /** 分页会话摘要，缓存仅接受不早于当前 revision 的元数据。 */
  async conversations({ after = "", limit = 50 } = {}) {
    const result = await this.request(
      "GET",
      `/conversations?${query({ after, limit })}`,
    );
    await this.apply({ conversations: result.items });
    return result;
  }
  /** 单聊历史分页，不推进账号增量游标。 */
  async conversationHistory(id, { beforeSeq, limit = 50 } = {}) {
    const result = await this.request(
      "GET",
      `/conversations/${encodeURIComponent(id)}/messages?${query({ limit, before_seq: beforeSeq })}`,
    );
    await this.apply({ conversations: [result.conversation] });
    return result;
  }
  /** 发送文字或 Unicode 表情；业务重试必须复用稳定 clientMessageId。 */
  async sendText(id, { clientMessageId, text }) {
    if (typeof clientMessageId !== "string" || !clientMessageId)
      throw new Error("stable clientMessageId required");
    const result = await this.request(
      "POST",
      `/conversations/${encodeURIComponent(id)}/messages`,
      {
        client_message_id: clientMessageId,
        type: "chat.text",
        payload: { text },
      },
    );
    validateMessage(result);
    await this.sync();
    return result;
  }
  /** 标记当前单聊实际可见的阅读水位。 */
  async markConversationRead(id, upToSeq) {
    const result = await this.request(
      "PUT",
      `/conversations/${encodeURIComponent(id)}/read`,
      { up_to_seq: upToSeq },
    );
    await this.apply({ conversations: [result] });
    await this.sync();
    return result;
  }
  /** 从服务端验证当前用户对单条消息的访问权限。 */
  async getMessage(id) {
    return validateMessage(
      await this.request("GET", `/messages/${encodeURIComponent(id)}`),
    );
  }
  /** 绑定或解除当前设备推送 token；原生 token 由宿主取得。 */
  async updatePushToken(token) {
    return this.request(
      "PUT",
      `/devices/${encodeURIComponent(this.deviceId)}/push-token`,
      { token },
    );
  }
  /** 获取当前设备推送偏好。 */
  async pushPreferences() {
    return this.request(
      "GET",
      `/devices/${encodeURIComponent(this.deviceId)}/preferences`,
    );
  }
  /** 设置当前设备推送 token、静音和正文预览。 */
  async setPush(token, { muted = false, preview = false } = {}) {
    return this.request(
      "PUT",
      `/devices/${encodeURIComponent(this.deviceId)}/push-token`,
      { token, muted, preview },
    );
  }
  /** 单连接实时订阅；断线先 HTTP 补拉，再用短期 ticket 重连。 */
  startRealtime() {
    this.check();
    if (this.realtimeActive) return;
    if (!this.socketConnector) throw new Error("Socket connector required");
    this.realtimeActive = true;
    const generation = ++this.realtimeGeneration;
    const valid = () =>
      !this.closed &&
      this.realtimeActive &&
      generation === this.realtimeGeneration;
    let delay = 250;
    const connect = async () => {
      if (!valid()) return;
      let socket,
        openTimer,
        done = false,
        pending = 0,
        queue = Promise.resolve();
      const finish = (error) => {
        if (done) return;
        done = true;
        clearTimeout(openTimer);
        socket?.close();
        if (this.channel === socket) this.channel = null;
        if (!valid()) return;
        if (error) this.onError(error);
        this.state("retrying");
        this.realtimeTimer = setTimeout(
          connect,
          delay + Math.floor(Math.random() * 100),
        );
        delay = Math.min(delay * 2, 30000);
      };
      try {
        await this.sync();
        if (!valid()) return;
        const session = await this.request("POST", "/session", {
          device_id: this.deviceId,
        });
        if (!valid()) return;
        if (session.app_id !== this.appId || session.user_id !== this.userId) {
          const e = new Error("消息身份不匹配");
          e.code = "IDENTITY_MISMATCH";
          throw e;
        }
        if (typeof session.ticket !== "string" || !session.ticket)
          throw new Error("Missing realtime ticket");
        this.state("connecting");
        const url = `${this.baseUrl.replace(/^http/, "ws")}/api/v1/im/ws?${query({ ticket: session.ticket, cursor: this.cache.cursor })}`;
        this.socketOpenTimer = openTimer = setTimeout(
          () => finish(new Error("Realtime connect timeout")),
          10000,
        );
        socket = this.socketConnector({
          url,
          onOpen: () => {
            if (valid() && !done) {
              clearTimeout(openTimer);
              delay = 250;
              this.state("online");
            }
          },
          onMessage: (raw) => {
            if (!valid() || done) return;
            if (++pending > 32) {
              finish(new Error("Realtime queue overflow"));
              return;
            }
            queue = queue
              .then(async () => {
                if (!valid() || done) return;
                const envelope = this.codec.decode(raw);
                if (envelope.type !== "sync" || !envelope.data?.cursor)
                  throw new Error("Invalid realtime frame");
                await this.apply(envelope.data, false, () => valid() && !done);
                if (valid() && !done)
                  await socket.send(
                    this.codec.encode({ cursor: envelope.data.cursor }),
                  );
              })
              .catch(finish)
              .finally(() => {
                pending--;
              });
          },
          onClose: () => finish(),
          onError: finish,
        });
        if (!valid() || done) {
          socket.close();
          return;
        }
        this.channel = socket;
      } catch (error) {
        if (!valid()) return;
        if (error.status === 401 || error.code === "IDENTITY_MISMATCH") {
          this.pauseRealtime();
          this.state("authRequired");
          this.onError(error);
          return;
        }
        finish(error);
      }
    };
    void connect();
  }
  /** 后台暂停实时连接及重连定时器；恢复时调用 startRealtime。 */
  pauseRealtime() {
    this.realtimeActive = false;
    this.realtimeGeneration++;
    clearTimeout(this.realtimeTimer);
    clearTimeout(this.socketOpenTimer);
    this.realtimeTimer = null;
    const socket = this.channel;
    this.channel = null;
    socket?.close();
  }
}

/** 构造相对接口查询，保留所有序号为原始字符串。 */
function query(values) {
  if (
    values.limit !== undefined &&
    (!Number.isInteger(values.limit) || values.limit < 1 || values.limit > 100)
  )
    throw new Error("Invalid page limit");
  return Object.entries(values)
    .filter(([, v]) => v !== undefined && v !== null)
    .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`)
    .join("&");
}
