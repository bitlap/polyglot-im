class RetryPolicy {
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
