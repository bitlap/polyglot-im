/// 网络请求的有界指数退避参数；重试沿用原发送意图的幂等键。
class RetryPolicy {
  /// 配置最大重试次数、初始延迟和抖动；所有次数与时限必须非负。
  RetryPolicy({
    this.maxRetries = 3,
    this.initialDelay = const Duration(milliseconds: 250),
    this.maxRetryAfter = const Duration(seconds: 30),
    this.jitterMilliseconds = 100,
  }) {
    if (maxRetries < 0 ||
        initialDelay.isNegative ||
        maxRetryAfter.isNegative ||
        jitterMilliseconds < 0) {
      throw ArgumentError('retry settings must be nonnegative');
    }
  }
  final int maxRetries, jitterMilliseconds;
  final Duration initialDelay, maxRetryAfter;
}
