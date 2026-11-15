from ..common.errors import ImError
from ..domain.outbox import OutboxCompletion, OutboxState
from ..ports.notifications import NotificationPublisher
from ..ports.outbox import Outbox


class OutboxDispatcher:
    """仅依赖存储和发布端口的派发服务；宿主负责周期调度与取消，不创建数据库或网络资源。"""

    def __init__(self, outbox: Outbox, sender: NotificationPublisher, *, lease_seconds: int = 120):
        """绑定借入端口并校验正整数租约时长；关闭责任仍由宿主承担。"""
        if (
            isinstance(lease_seconds, bool)
            or not isinstance(lease_seconds, int)
            or lease_seconds <= 0
        ):
            raise ValueError("lease_seconds must be a positive integer")
        self.outbox, self.sender, self.lease_seconds = outbox, sender, lease_seconds

    async def drain_once(self) -> bool:
        """领取后在事务外发送；无记录返回 false，取消或未知异常不确认，等待租约过期重放。"""
        row = await self.outbox.claim(self.lease_seconds)
        if row is None:
            return False
        state, code = OutboxState.SENT, None
        try:
            await self.sender.send(
                row.event.recipient, row.event.event_id, row.event.kind, row.event.payload
            )
        except ImError as error:
            state = (
                OutboxState.DEAD
                if error.status in {400, 403, 409, 413, 422}
                else OutboxState.PENDING
            )
            code = error.code
        except (OSError, TimeoutError):
            state, code = OutboxState.PENDING, "NETWORK_ERROR"
        await self.outbox.complete(
            row, OutboxCompletion(state, min(300, 2 ** min(row.attempts, 9)), code)
        )
        return True
