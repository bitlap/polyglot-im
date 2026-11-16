export { uniCache, uniCache as uniStorage } from "./cache/uni_cache.js";

/** 默认 uni.request 传输，支持中止；令牌仅进入 Authorization。 */
export const uniTransport = (options) =>
  new Promise((resolve, reject) => {
    const { signal, ...request } = options;
    if (signal?.aborted) {
      reject(new Error("Request aborted"));
      return;
    }
    const abort = () => {
      task.abort();
      reject(new Error("Request aborted"));
    };
    const cleanup = () => signal?.removeEventListener("abort", abort);
    const task = uni.request({
      ...request,
      success: (value) => {
        cleanup();
        resolve(value);
      },
      fail: () => {
        cleanup();
        reject(new Error("消息网络连接失败"));
      },
    });
    signal?.addEventListener("abort", abort);
  });
/** 管理当前 SocketTask 的连接状态；取消握手时等打开后关闭，终态不再调用原生任务。 */
export function uniSocketConnector({
  url,
  onOpen,
  onMessage,
  onClose,
  onError,
}) {
  let state = "connecting", closeSent = false;
  const task = uni.connectSocket({ url, complete: () => {} });
  const failed = (error) => {
    if (state === "closed") return;
    state = "closed";
    onError(error);
  };
  const closeNative = () => {
    if (closeSent) return;
    closeSent = true;
    try {
      task.close({ code: 1000, reason: "client closing", fail: failed });
    } catch (error) {
      failed(error);
    }
  };
  task.onOpen(() => {
    if (state === "closing") { closeNative(); return; }
    if (state !== "connecting") return;
    state = "open";
    onOpen();
  });
  task.onMessage((event) => { if (state === "open") onMessage(event.data); });
  task.onClose((event) => {
    if (state === "closed") return;
    state = "closed";
    onClose(event);
  });
  task.onError(failed);
  return {
    send: (data) => {
      if (state !== "open") return Promise.reject(new Error("Socket is not open"));
      return new Promise((resolve, reject) =>
        task.send({ data, success: resolve, fail: reject }),
      );
    },
    close: () => {
      if (state === "closing" || state === "closed") return;
      const opened = state === "open";
      state = "closing";
      // 微信连接尚未打开时，提前 close 可能失败；保留取消意图处理迟到的 onOpen。
      if (opened) closeNative();
    },
  };
}
