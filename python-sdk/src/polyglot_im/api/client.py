from ..adapters.codec.json import JsonCodec
from ..adapters.websocket import WebSocketConnector
from ..domain.identity import Scope
from ..ports.api import RemoteApi
from ..ports.codec import WireCodec
from ..ports.socket import SocketConnector
from ..ports.storage import LocalStore
from ..service.client import SyncClient


class ImClient(SyncClient):
    """兼容的组合入口；默认适配器仅在显式运行同步时使用外部资源。"""

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
