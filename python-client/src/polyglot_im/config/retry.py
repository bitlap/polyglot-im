from dataclasses import dataclass


@dataclass(frozen=True)
class RetryPolicy:
    """网络请求的有界指数退避参数；重试沿用原发送意图的幂等键。"""

    max_retries: int = 3
    initial_delay: float = 0.25
    max_delay: float = 8
    jitter: float = 0.1
    retry_after_limit: float = 30

    def __post_init__(self):
        """在数据模型构造后校验字段约束，非法输入不进入消息或请求流程。"""
        if (
            self.max_retries < 0
            or min(self.initial_delay, self.max_delay, self.jitter, self.retry_after_limit) < 0
        ):
            raise ValueError("retry settings must be nonnegative")

    def delay(self, attempt: int) -> float:
        """计算第 attempt 次重试的有上限指数延迟，单位为秒。"""
        return min(self.max_delay, self.initial_delay * 2**attempt)
