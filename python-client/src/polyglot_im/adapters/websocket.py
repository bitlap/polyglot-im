from contextlib import asynccontextmanager


class WebSocketConnector:
    """使用 WebSocket 库创建实时会话，默认适配器在实际连接时打开网络。"""

    @asynccontextmanager
    async def connect(self, url: str):
        """建立目标实时会话；连接失败交给调用方处理，退出时释放底层连接。"""
        from websockets.asyncio.client import connect

        async with connect(
            url, max_size=2**20, ping_interval=20, ping_timeout=30, max_queue=4
        ) as socket:
            yield socket
