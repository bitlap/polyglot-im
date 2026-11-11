from ..adapters.codec.json import JsonCodec
from ..adapters.websocket import WebSocketConnector
from ..domain.identity import Scope
from ..ports.api import RemoteApi
from ..ports.codec import WireCodec
from ..ports.socket import SocketConnector
from ..ports.storage import LocalStore
from ..service.client import SyncClient


class ImClient(SyncClient):
    """组合账号同步、JSON 编解码与 WebSocket 的客户端入口；外部连接按需打开。"""

    def __init__(
        self,
        api: RemoteApi,
        base_url: str,
        scope: Scope,
        device_id: str,
        store: LocalStore,
        *,
        socket_connector: SocketConnector | None = None,
        codec: WireCodec | None = None,
    ):
        """绑定账号、设备和持久缓存；使用默认或注入的 SocketConnector 与 WireCodec。"""
        super().__init__(
            api,
            base_url,
            scope,
            device_id,
            store,
            socket_connector=socket_connector
            if socket_connector is not None
            else WebSocketConnector(),
            codec=codec if codec is not None else JsonCodec(),
        )
