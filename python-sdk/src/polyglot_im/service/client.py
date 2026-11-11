import asyncio
import json
import random
from collections.abc import AsyncIterator
from typing import Any
from urllib.parse import quote, urlencode, urlsplit, urlunsplit

from ..common.errors import ImError
from ..config.protocol import API_PREFIX
from ..domain.cache import empty_cache
from ..domain.identity import Scope
from ..domain.message import Message
from ..ports.api import RemoteApi
from ..ports.codec import WireCodec
from ..ports.socket import SocketConnector
from ..ports.storage import LocalStore


class SyncClient:
    def __init__(
        self,
        api: RemoteApi,
        base_url: str,
        scope: Scope,
        device_id: str,
        store: LocalStore,
        *,
        socket_connector: SocketConnector,
        codec: WireCodec,
    ):
        self.socket_connector, self.codec = socket_connector, codec
        self.api, self.base_url, self.scope, self.device_id, self.store = (
            api.scoped(scope),
            base_url.rstrip("/"),
            scope,
            device_id,
            store,
        )
        if scope.environment.rstrip("/") != self.base_url:
            raise ValueError("cache environment must match base_url")
        self.cache = empty_cache()
        self.connection_state = "idle"
        self._lock = asyncio.Lock()
        self._closed = False
        self._watch_task: asyncio.Task | None = None

    def _check_open(self):
        if self._closed:
            raise RuntimeError("client is closed; create a new scoped client")

    async def _session(self):
        session = await self.api.request("POST", "/session", {"device_id": self.device_id})
        self._check_open()
        if session["app_id"] != self.scope.app_id or session["user_id"] != self.scope.user_id:
            raise ImError(403, "IDENTITY_MISMATCH", "credential does not match local account scope")
        return session

    async def initialize(self):
        self._check_open()
        self.cache = await self.store.load(self.scope)
        await self._session()
        return await self.sync()

    async def sync(self):
        self._check_open()
        async with self._lock:
            self.connection_state = "syncing"
            try:
                while True:
                    try:
                        if not self.cache["cursor"]:
                            page = await self.api.request("GET", "/snapshot")
                            reset = True
                        else:
                            page = await self.api.request(
                                "GET", "/sync?" + urlencode({"cursor": self.cache["cursor"]})
                            )
                            reset = False
                    except ImError as error:
                        if error.code != "RESET_REQUIRED":
                            raise
                        page, reset = await self.api.request("GET", "/snapshot"), True
                    self._check_open()
                    self.cache = await self.store.commit(self.scope, page, reset)
                    self._check_open()
                    if not page.get("has_more", False):
                        break
                self.connection_state = "online"
                return self.cache
            except ImError as error:
                self.connection_state = "authRequired" if error.status == 401 else "retrying"
                raise

    @property
    def messages(self) -> list[Message]:
        return sorted(
            (Message.from_dict(m) for m in self.cache["messages"].values()),
            key=lambda m: int(m.conversation_seq),
            reverse=True,
        )

    async def history(self, before_seq: str | None = None, limit: int = 50):
        self._check_open()
        query = {"limit": str(limit)}
        if before_seq is not None:
            query["before_seq"] = before_seq
        result = await self.api.request("GET", "/messages?" + urlencode(query))
        self._check_open()
        self.cache = await self.store.commit(self.scope, {"messages": result["items"]})
        return result

    async def _commit_conversations(self, patches: list[dict]):
        async with self._lock:
            self._check_open()
            self.cache = await self.store.commit(self.scope, {"conversations": patches})
            self._check_open()

    async def create_direct(self, peer_user_id: str) -> dict:
        self._check_open()
        result = await self.api.request(
            "POST", "/conversations/direct", {"peer_user_id": peer_user_id}
        )
        await self._commit_conversations([result])
        return result

    async def conversations(self, after: str | None = None, limit: int = 50) -> dict:
        self._check_open()
        result = await self.api.request(
            "GET", "/conversations?" + urlencode({"after": after or "", "limit": limit})
        )
        await self._commit_conversations(result["items"])
        return result

    async def conversation_history(
        self, conversation_id: str, before_seq: str | None = None, limit: int = 50
    ) -> dict:
        self._check_open()
        query = {"limit": str(limit)}
        if before_seq is not None:
            query["before_seq"] = before_seq
        result = await self.api.request(
            "GET",
            "/conversations/" + quote(conversation_id, safe="") + "/messages?" + urlencode(query),
        )
        await self._commit_conversations([result["conversation"]])
        return result

    async def send_text(self, conversation_id: str, client_message_id: str, text: str) -> Message:
        self._check_open()
        if not client_message_id:
            raise ValueError("stable client_message_id required")
        result = await self.api.request(
            "POST",
            "/conversations/" + quote(conversation_id, safe="") + "/messages",
            {
                "client_message_id": client_message_id,
                "type": "chat.text",
                "payload": {"text": text},
            },
        )
        await self.sync()
        return Message.from_dict(result)

    async def mark_conversation_read(self, conversation_id: str, up_to_seq: str) -> dict:
        self._check_open()
        result = await self.api.request(
            "PUT",
            "/conversations/" + quote(conversation_id, safe="") + "/read",
            {"up_to_seq": up_to_seq},
        )
        await self._commit_conversations([result])
        await self.sync()
        return result

    async def get_message(self, message_id: str) -> Message:
        self._check_open()
        return Message.from_dict(
            await self.api.request("GET", "/messages/" + quote(message_id, safe=""))
        )

    async def mark_read(self, up_to_seq: str | None = None):
        self._check_open()
        # 默认值在请求前捕获，不能误清之后到达的消息。
        target = up_to_seq if up_to_seq is not None else self.cache["latest_seq"]
        await self.api.request("PUT", "/read", {"up_to_seq": target})
        return await self.sync()

    async def set_push(self, token: str | None, *, muted: bool = False, preview: bool = False):
        self._check_open()
        return await self.api.request(
            "PUT",
            f"/devices/{self.device_id}/push-token",
            {"token": token, "muted": muted, "preview": preview},
        )

    async def watch(self) -> AsyncIterator[dict[str, Any]]:
        """至少一次接收，落盘后才确认；无凭证错误时停止自动重连。"""

        self._check_open()
        if self._watch_task is not None:
            raise RuntimeError("only one watch iterator is allowed per client")
        self._watch_task = asyncio.current_task()
        delay = 0.25
        last = None
        try:
            while not self._closed:
                try:
                    await self.sync()
                    session = await self._session()
                    parsed = urlsplit(self.base_url)
                    query = urlencode({"ticket": session["ticket"], "cursor": self.cache["cursor"]})
                    url = urlunsplit(
                        (
                            "wss" if parsed.scheme == "https" else "ws",
                            parsed.netloc,
                            parsed.path + API_PREFIX + "/ws",
                            query,
                            "",
                        )
                    )
                    self.connection_state = "connecting"
                    async with self.socket_connector.connect(url) as websocket:
                        self.connection_state, delay = "online", 0.25
                        async for raw in websocket:
                            page = self.codec.decode(raw)["data"]
                            async with self._lock:
                                self._check_open()
                                self.cache = await self.store.commit(self.scope, page)
                                self._check_open()
                            await websocket.send(self.codec.encode({"cursor": page["cursor"]}))
                            encoded = json.dumps(self.cache, sort_keys=True)
                            if encoded != last:
                                last = encoded
                                yield self.cache
                except ImError as error:
                    if error.status == 401 or error.code == "IDENTITY_MISMATCH":
                        self.connection_state = "authRequired"
                        raise
                    self.connection_state = "retrying"
                except (OSError, TimeoutError):
                    self.connection_state = "retrying"
                except Exception:
                    if self._closed:
                        break
                    self.connection_state = "retrying"
                if not self._closed:
                    await asyncio.sleep(delay + random.random() * 0.1)
                    delay = min(delay * 2, 30)
        finally:
            self._watch_task = None

    async def close(self, *, logout: bool = True, clear_cache: bool = False):
        if self._closed:
            return
        self._closed = True
        task = self._watch_task
        if task is not None and task is not asyncio.current_task():
            task.cancel()
            try:
                await task
            except asyncio.CancelledError:
                pass
        self.connection_state = "idle"
        try:
            if logout:
                await self.api.request("DELETE", f"/devices/{self.device_id}/session")
        finally:
            if clear_cache:
                await self.store.clear(self.scope)
