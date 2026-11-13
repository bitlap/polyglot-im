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
    """管理一个账号的消息缓存、增量同步和实时连接；切换账号时应创建新实例。"""

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
        """绑定账号作用域、设备、缓存和实时适配器；初始化前不读取服务器消息。"""
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
        """加载当前账号缓存、建立设备会话并补齐可靠消息状态。"""
        self._check_open()
        self.cache = await self.store.load(self.scope)
        await self._session()
        return await self.sync()

    async def sync(self):
        """串行追赶增量；游标过期时取快照，缓存与游标提交成功后才发布新状态。"""
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
            except Exception:
                if not self._closed:
                    self.connection_state = "retrying"
                raise

    @property
    def messages(self) -> list[Message]:
        """读取当前已提交缓存中的通知消息，按会话序号倒序排列。"""
        return sorted(
            (
                Message.from_dict(m)
                for m in self.cache["messages"].values()
                if not m.get("is_deleted", False)
            ),
            key=lambda m: int(m.conversation_seq),
            reverse=True,
        )

    async def history(self, before_seq: str | None = None, limit: int = 50):
        """按 before_seq 向前分页读取通知历史；历史页不推进增量游标。"""
        self._check_open()
        query = {"limit": str(limit)}
        if before_seq is not None:
            query["before_seq"] = before_seq
        result = await self.api.request("GET", "/messages?" + urlencode(query))
        self._check_open()
        self.cache = await self.store.commit(
            self.scope,
            {
                "messages": result["items"],
                **(
                    {"history_position": result["event_position"]}
                    if "event_position" in result
                    else {}
                ),
            },
        )
        return result

    async def _commit_conversations(self, patches: list[dict]):
        async with self._lock:
            self._check_open()
            self.cache = await self.store.commit(self.scope, {"conversations": patches})
            self._check_open()

    async def create_direct(self, peer_user_id: str) -> dict:
        """按对方用户身份创建或取得唯一单聊会话。"""
        self._check_open()
        result = await self.api.request(
            "POST", "/conversations/direct", {"peer_user_id": peer_user_id}
        )
        await self._commit_conversations([result])
        return result

    async def conversations(self, after: str | None = None, limit: int = 50) -> dict:
        """从 after 位置分页取得当前用户可见的单聊会话摘要。"""
        self._check_open()
        result = await self.api.request(
            "GET", "/conversations?" + urlencode({"after": after or "", "limit": limit})
        )
        await self._commit_conversations(result["items"])
        return result

    async def conversation_history(
        self, conversation_id: str, before_seq: str | None = None, limit: int = 50
    ) -> dict:
        """读取指定单聊的历史页并合并会话缓存，不推进账号同步游标。"""
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
        """发送文字或 Unicode 表情；重试必须保留 client_message_id，成功后更新本地同步状态。"""
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
        """提交指定会话实际可见的阅读水位；水位只增，不替其他会话标记已读。"""
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
        """读取当前账号有权访问的一条消息；不可见与不存在使用相同错误边界。"""
        self._check_open()
        return Message.from_dict(
            await self.api.request("GET", "/messages/" + quote(message_id, safe=""))
        )

    async def mark_messages_read(self, ids: list[str]):
        """原子标记 1–100 条指定通知已读，不影响其他消息。"""
        return await self._update_notifications(ids, False)

    async def delete_messages(self, ids: list[str]):
        """原子删除 1–100 条当前用户通知，删除状态可靠同步。"""
        return await self._update_notifications(ids, True)

    async def _update_notifications(self, ids: list[str], delete: bool):
        self._check_open()
        if not ids or len(ids) > 100 or len(set(ids)) != len(ids):
            raise ValueError("1 to 100 unique message IDs required")
        await self.api.request(
            "POST" if delete else "PUT",
            "/notifications/delete" if delete else "/notifications/read",
            {"message_ids": list(ids)},
        )
        self._check_open()
        return await self.sync()

    async def mark_read(self, up_to_seq: str | None = None):
        """标记通知阅读位置；省略序号时捕获当前已知高水位，不误清之后的新消息。"""
        self._check_open()
        # 默认值在请求前捕获，不能误清之后到达的消息。
        target = up_to_seq if up_to_seq is not None else self.cache["latest_seq"]
        await self.api.request("PUT", "/read", {"up_to_seq": target})
        return await self.sync()

    async def set_push(self, token: str | None, *, muted: bool = False, preview: bool = False):
        """更新设备 token、静音和锁屏正文偏好；token 为空表示解绑。"""
        self._check_open()
        return await self.api.request(
            "PUT",
            f"/devices/{quote(self.device_id, safe='')}/push-token",
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
        """终止当前客户端；按参数注销设备或清理当前作用域缓存，不再接收迟到更新。"""
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
                await self.api.request(
                    "DELETE", f"/devices/{quote(self.device_id, safe='')}/session"
                )
        finally:
            if clear_cache:
                await self.store.clear(self.scope)
