import { RetryPolicy } from "../config/retry.js";

/** 可借入的认证 HTTP API；用户 scope 可选，服务端发送使用服务凭证且不绑定用户。 */
export class HttpApi {
  constructor({
    baseUrl,
    appId,
    userId,
    tokenProvider,
    refreshToken,
    transport,
    codec = JSON,
    retry = new RetryPolicy(),
    ownsTransport = false,
  }) {
    this.baseUrl = baseUrl.replace(/\/+$/, "");
    if (
      !/^https:\/\/[^/?#@\s]+(?:\/[^?#\s]*)?$/.test(this.baseUrl) &&
      !/^http:\/\/(localhost|127\.0\.0\.1)(:\d+)?(?:\/[^?#\s]*)?$/.test(
        this.baseUrl,
      )
    )
      throw new Error("消息服务地址不安全");
    if (!!appId !== !!userId)
      throw new Error("Both appId and userId required for account binding");
    Object.assign(this, {
      appId,
      userId,
      tokenProvider,
      refreshToken,
      transport,
      retry,
      ownsTransport,
    });
    this.codec = {
      encode: (value) => (codec.encode || codec.stringify).call(codec, value),
      decode: (value) => (codec.decode || codec.parse).call(codec, value),
    };
    this.pending = new Set();
    this.closed = false;
    this.authVersion = 0;
    this.refreshing = null;
  }
  /** 每次动态读取凭证；401 合并刷新，瞬时网络错误有界重试且保持请求体不变。 */
  async request(method, path, data) {
    this.check();
    if (!/^\/(?!\/)[^#\\]*$/.test(path) || /[\r\n]/.test(path))
      throw new Error("Invalid API path");
    for (const segment of path.split("?")[0].slice(1).split("/")) {
      const decoded = decodeURIComponent(segment);
      if (decoded === "." || decoded === ".." || /[\\/\r\n]/.test(decoded))
        throw new Error("Invalid API path");
    }
    const frozen = data === undefined ? undefined : this.codec.encode(data);
    const authVersion = this.authVersion;
    let authRetried = false,
      attempt = 0;
    const signal = {
      aborted: false,
      listeners: new Set(),
      addEventListener: (_, f) => signal.listeners.add(f),
      removeEventListener: (_, f) => signal.listeners.delete(f),
    };
    const cancel = () => {
      signal.aborted = true;
      for (const f of signal.listeners) f();
    };
    this.pending.add(cancel);
    try {
      while (true) {
        this.check();
        const token = await this.tokenProvider();
        if (typeof token !== "string" || !token || /[\r\n]/.test(token))
          throw new Error("请重新登录");
        this.check();
        let response;
        try {
          response = await this.transport({
            url: `${this.baseUrl}/api/v1/im${path}`,
            method,
            data: frozen === undefined ? undefined : this.codec.decode(frozen),
            timeout: 10000,
            signal,
            header: {
              "Content-Type": "application/json",
              Authorization: `Bearer ${token}`,
              ...(this.appId
                ? {
                    "X-IM-App-ID": encodeURIComponent(this.appId),
                    "X-IM-User-ID": encodeURIComponent(this.userId),
                  }
                : {}),
            },
          });
        } catch (error) {
          this.check();
          if (attempt >= this.retry.maxRetries) throw error;
          await this.wait(this.retry.delay(attempt++));
          continue;
        }
        this.check();
        if (response.statusCode === 401 && this.refreshToken && !authRetried) {
          authRetried = true;
          if (authVersion === this.authVersion) {
            if (!this.refreshing)
              this.refreshing = Promise.resolve()
                .then(() => this.refreshToken())
                .then(() => {
                  this.authVersion++;
                })
                .finally(() => {
                  this.refreshing = null;
                });
            await this.refreshing;
          }
          continue;
        }
        if (
          [429, 500, 502, 503, 504].includes(response.statusCode) &&
          attempt < this.retry.maxRetries
        ) {
          await this.wait(this.retry.delay(attempt++, response.header));
          continue;
        }
        const body =
          typeof response.data === "string"
            ? this.codec.decode(response.data)
            : response.data;
        if (
          response.statusCode < 200 ||
          response.statusCode >= 300 ||
          body?.code !== 200
        ) {
          const error = new Error(body?.message || "消息请求失败");
          error.status = response.statusCode;
          error.code = body?.error_code;
          throw error;
        }
        if (!body.data || typeof body.data !== "object")
          throw new Error("消息响应格式错误");
        return body.data;
      }
    } finally {
      this.pending.delete(cancel);
    }
  }
  /** 拒绝关闭实例继续访问账号。 */
  check() {
    if (this.closed) throw new Error("消息账号已关闭");
  }
  /** 可被关闭操作取消的等待，不依赖平台 AbortController。 */
  wait(ms) {
    this.check();
    return new Promise((resolve, reject) => {
      const cancel = () => {
        clearTimeout(timer);
        this.pending.delete(cancel);
        reject(new Error("消息账号已关闭"));
      };
      const timer = setTimeout(() => {
        this.pending.delete(cancel);
        resolve();
      }, ms);
      this.pending.add(cancel);
    });
  }
  /** 终止等待和在途请求，仅关闭显式拥有的传输资源。 */
  async close() {
    if (this.closed) return;
    this.closed = true;
    for (const cancel of [...this.pending]) cancel();
    if (this.ownsTransport) await this.transport.close?.();
  }
}
