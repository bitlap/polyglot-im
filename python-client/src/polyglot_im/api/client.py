from ..adapters.codec.json import JsonCodec
from ..adapters.websocket import WebSocketConnector
from ..domain.identity import Scope
from ..ports.api import RemoteApi
from ..ports.client_cache import ClientCache
from ..ports.codec import WireCodec
from ..ports.socket import SocketConnector
from ..service.client import SyncClient


class ImClient(SyncClient):
    """组合账号同步、JSON 编解码与 WebSocket 的客户端入口；外部连接按需打开。"""

    def __init__(
        self,
        api: RemoteApi,
        base_url: str,
        scope: Scope,
        device_id: str,
        cache_persistence: ClientCache | None = None,
        *,
        store: ClientCache | None = None,
        socket_connector: SocketConnector | None = None,
        codec: WireCodec | None = None,
    ):
        """绑定账号、设备和持久缓存；cache_persistence 与旧 store 必须择一，网络资源按需打开。"""
        if (cache_persistence is None) == (store is None):
            raise ValueError("provide exactly one cache_persistence")
        cache_persistence = cache_persistence if cache_persistence is not None else store
        super().__init__(
            api,
            base_url,
            scope,
            device_id,
            cache_persistence,
            socket_connector=socket_connector
            if socket_connector is not None
            else WebSocketConnector(),
            codec=codec if codec is not None else JsonCodec(),
        )
