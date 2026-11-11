"""可靠通知客户端：HTTP 补拉 + SQLite 原子游标 + 可重连 WebSocket。"""

from __future__ import annotations

import asyncio
import hashlib
import json
import random
import sqlite3
from collections.abc import AsyncIterator, Awaitable, Callable
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Protocol
from urllib.parse import quote, urlencode, urlsplit, urlunsplit

from .message import Message
from .transport import HttpRequest, HttpTransport

API_PREFIX = "/api/v1/im"


class ImError(Exception):
    def __init__(self, status: int, code: str, message: str):
        super().__init__(message)
        self.status, self.code = status, code


@dataclass(frozen=True)
class Scope:
    environment: str
    app_id: str
    user_id: str

    @property
    def key(self) -> str:
        return hashlib.sha256(
            json.dumps([self.environment, self.app_id, self.user_id]).encode()
        ).hexdigest()


def empty_cache() -> dict[str, Any]:
    return {
        "messages": {},
        "cursor": None,
        "event_position": "0",
        "latest_seq": "0",
        "read_seq": "0",
        "unread_count": "0",
        "chat_unread_count": "0",
    }


def apply_page(
    current: dict[str, Any], page: dict[str, Any], reset: bool = False
) -> dict[str, Any]:
    if int(page.get("event_position", current["event_position"])) < int(current["event_position"]):
        return current
    result = empty_cache() if reset else json.loads(json.dumps(current))
    for message in page.get("messages", []):
        parsed = Message.from_dict(message)
        result["messages"][parsed.message_id] = parsed.to_dict()
    for event in page.get("events", []):
        if event["type"] == "message.created":
            parsed = Message.from_dict(event["data"])
            result["messages"][parsed.message_id] = parsed.to_dict()
    conversations = result.setdefault("conversations", {})
    patches = list(page.get("conversations", [])) + [
        e["data"] for e in page.get("events", []) if e["type"] == "conversation.updated"
    ]
    for patch in patches:
        cid = patch["conversation_id"]
        previous = conversations.get(cid, {"revision": "0", "messages": {}})
        messages = dict(previous["messages"])
        for value in patch.get("messages", []):
            parsed = Message.from_dict(value)
            if parsed.conversation_id != cid:
                raise ValueError("message conversation mismatch")
            messages[parsed.message_id] = parsed.to_dict()
        metadata = previous if int(previous["revision"]) > int(patch["revision"]) else patch
        conversations[cid] = {**metadata, "messages": messages}
    for name in (
        "cursor",
        "event_position",
        "latest_seq",
        "read_seq",
        "unread_count",
        "chat_unread_count",
        "conversation_id",
    ):
        if name in page:
            result[name] = page[name]
    for name in ("latest_seq", "read_seq"):
        if not reset:
            result[name] = str(max(int(current[name]), int(result[name])))
    result["unread_count"] = str(int(result["latest_seq"]) - int(result["read_seq"]))
    return result


class LocalStore(Protocol):
    async def load(self, scope: Scope) -> dict[str, Any]: ...
    async def commit(
        self, scope: Scope, page: dict[str, Any], reset: bool = False
    ) -> dict[str, Any]: ...
    async def clear(self, scope: Scope) -> None: ...


class SqliteStore:
    """每次操作独立连接，消息与游标在同一 SQLite 事务提交；无全局事件循环。"""

    def __init__(self, path: str | Path):
        self.path = str(path)
        if self.path == ":memory:":
            raise ValueError("a persistent database path is required")
        Path(self.path).parent.mkdir(parents=True, exist_ok=True)

    def _run(
        self,
        scope: Scope,
        page: dict[str, Any] | None = None,
        reset: bool = False,
        clear: bool = False,
    ):
        with sqlite3.connect(self.path, timeout=15) as connection:
            connection.execute("PRAGMA journal_mode=WAL")
            connection.execute(
                "CREATE TABLE IF NOT EXISTS cache(scope TEXT PRIMARY KEY,data TEXT NOT NULL)"
            )
            connection.execute("BEGIN IMMEDIATE")
            row = connection.execute(
                "SELECT data FROM cache WHERE scope=?", (scope.key,)
            ).fetchone()
            current = json.loads(row[0]) if row else empty_cache()
            if clear:
                connection.execute("DELETE FROM cache WHERE scope=?", (scope.key,))
                return empty_cache()
            if page is not None:
                current = apply_page(current, page, reset)
                connection.execute(
                    "INSERT INTO cache(scope,data) VALUES(?,?) "
                    "ON CONFLICT(scope) DO UPDATE SET data=excluded.data",
                    (scope.key, json.dumps(current)),
                )
            return current

    async def load(self, scope: Scope) -> dict[str, Any]:
        return await asyncio.to_thread(self._run, scope)

    async def commit(
        self, scope: Scope, page: dict[str, Any], reset: bool = False
    ) -> dict[str, Any]:
        return await asyncio.to_thread(self._run, scope, page, reset)

    async def clear(self, scope: Scope) -> None:
        await asyncio.to_thread(self._run, scope, None, False, True)


class ApiClient:
    def __init__(
        self,
        transport: HttpTransport,
        refresh_auth: Callable[[], Awaitable[None]] | None = None,
        expected_scope: Scope | None = None,
    ):
        self.transport, self.refresh_auth = transport, refresh_auth
        self._refresh_lock = asyncio.Lock()
        self.expected_scope = expected_scope
        self._auth_generation = 0

    def scoped(self, scope: Scope):
        return ApiClient(self.transport, self.refresh_auth, scope)

    async def request(self, method: str, path: str, body: dict | None = None) -> dict:
        refreshed = False
        generation = self._auth_generation
        attempt = 0
        while True:
            try:
                response = await self.transport.execute(
                    HttpRequest(
                        method,
                        API_PREFIX + path,
                        headers={
                            "X-IM-App-ID": quote(self.expected_scope.app_id, safe=""),
                            "X-IM-User-ID": quote(self.expected_scope.user_id, safe=""),
                        }
                        if self.expected_scope
                        else {},
                        body=body,
                    )
                )
            except (OSError, TimeoutError):
                if attempt >= 3:
                    raise
                await asyncio.sleep(min(8, 0.25 * 2**attempt) + random.random() * 0.1)
                attempt += 1
                continue
            try:
                data = json.loads(response.body)
            except (ValueError, UnicodeDecodeError):
                if 200 <= response.status < 300:
                    raise
                data = {}
            if 200 <= response.status < 300:
                if isinstance(data.get("code"), int):
                    if data["code"] != 200 or not isinstance(data.get("data"), dict):
                        raise ValueError("invalid API response envelope")
                    return data["data"]
                return data
            if response.status == 401 and self.refresh_auth and not refreshed:
                async with self._refresh_lock:
                    if generation == self._auth_generation:
                        await self.refresh_auth()
                        self._auth_generation += 1
                refreshed = True
                continue
            if response.status in {429, 500, 502, 503, 504} and attempt < 3:
                delay = response.headers.get("retry-after", "0")
                wait = float(delay) if delay.isdigit() else 0
                await asyncio.sleep(
                    max(min(wait, 30), min(8, 0.25 * 2**attempt)) + random.random() * 0.1
                )
                attempt += 1
                continue
            raise ImError(
                response.status,
                data.get("error_code")
                or (data.get("code") if isinstance(data.get("code"), str) else "HTTP_ERROR"),
                data.get("message", "request failed"),
            )


class NotificationSender:
    def __init__(self, api: ApiClient):
        self.api = api

    async def send(
        self, recipient_id: str, client_message_id: str, type: str, payload: dict
    ) -> Message:
        if not client_message_id:
            raise ValueError("stable client_message_id required")
        result = await self.api.request(
            "POST",
            "/notifications",
            {
                "recipient_id": recipient_id,
                "client_message_id": client_message_id,
                "type": type,
                "payload": payload,
            },
        )
        return Message.from_dict(result)


class ImClient:
    def __init__(
        self, api: ApiClient, base_url: str, scope: Scope, device_id: str, store: LocalStore
    ):
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
        from websockets.asyncio.client import connect

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
                    async with connect(
                        url, max_size=2**20, ping_interval=20, ping_timeout=30, max_queue=4
                    ) as websocket:
                        self.connection_state, delay = "online", 0.25
                        async for raw in websocket:
                            page = json.loads(raw)["data"]
                            async with self._lock:
                                self._check_open()
                                self.cache = await self.store.commit(self.scope, page)
                                self._check_open()
                            await websocket.send(json.dumps({"cursor": page["cursor"]}))
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
