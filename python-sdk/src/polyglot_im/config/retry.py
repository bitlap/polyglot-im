from dataclasses import dataclass


@dataclass(frozen=True)
class RetryPolicy:
    max_retries: int = 3
    initial_delay: float = 0.25
    max_delay: float = 8
    jitter: float = 0.1
    retry_after_limit: float = 30

    def __post_init__(self):
        if (
            self.max_retries < 0
            or min(self.initial_delay, self.max_delay, self.jitter, self.retry_after_limit) < 0
        ):
            raise ValueError("retry settings must be nonnegative")

    def delay(self, attempt: int) -> float:
        return min(self.max_delay, self.initial_delay * 2**attempt)
