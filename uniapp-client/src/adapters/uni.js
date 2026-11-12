import { emptyCache } from "../domain/cache.js";

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
/** 默认持久化；配额错误向上传递，不用内存替代可靠存储。 */
export const uniStorage = {
  load: (key) => uni.getStorageSync(key) || emptyCache(),
  save: (key, value) => uni.setStorageSync(key, value),
  clear: (key) => uni.removeStorageSync(key),
};
/** 将 uni SocketTask 转为注入式回调端口，监听均绑定当前任务。 */
export function uniSocketConnector({
  url,
  onOpen,
  onMessage,
  onClose,
  onError,
}) {
  const task = uni.connectSocket({ url, complete: () => {} });
  task.onOpen(onOpen);
  task.onMessage((event) => onMessage(event.data));
  task.onClose(onClose);
  task.onError(onError);
  return {
    send: (data) =>
      new Promise((resolve, reject) =>
        task.send({ data, success: resolve, fail: reject }),
      ),
    close: () => task.close({}),
  };
}
