from contextlib import asynccontextmanager


class WebSocketConnector:
    @asynccontextmanager
    async def connect(self, url: str):
        from websockets.asyncio.client import connect

        async with connect(
            url, max_size=2**20, ping_interval=20, ping_timeout=30, max_queue=4
        ) as socket:
            yield socket
