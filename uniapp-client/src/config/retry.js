/** 有界 HTTP 重试策略；可配置重试次数、退避上限和抖动。 */
export class RetryPolicy {
  constructor({
    maxRetries = 3,
    initialDelay = 250,
    maxDelay = 30000,
    jitter = 100,
  } = {}) {
    if (
      ![maxRetries, initialDelay, maxDelay, jitter].every(
        (n) => Number.isInteger(n) && n >= 0,
      )
    )
      throw new Error("Invalid retry policy");
    Object.assign(this, { maxRetries, initialDelay, maxDelay, jitter });
  }
  /** 优先尊重 Retry-After 秒数，并限制最长等待时间。 */
  delay(attempt, headers = {}) {
    const value = Object.entries(headers).find(
      ([key]) => key.toLowerCase() === "retry-after",
    )?.[1];
    const seconds = value === undefined ? NaN : Number(value);
    return Number.isFinite(seconds) && seconds >= 0
      ? Math.min(seconds * 1000, this.maxDelay)
      : Math.min(this.initialDelay * 2 ** attempt, this.maxDelay) +
          Math.floor(Math.random() * (this.jitter + 1));
  }
}
