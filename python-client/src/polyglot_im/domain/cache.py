import json
from typing import Any

from .message import Message


def empty_cache() -> dict[str, Any]:
    """返回无消息、无游标且各计数为零的初始缓存。"""
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
    """合并消息版本与账号水位；通知按单条标志或水位判已读，历史补页不回退状态，不改变版本。"""
    if int(
        page.get("history_position", page.get("event_position", current["event_position"]))
    ) < int(current["event_position"]):
        return current
    result = empty_cache() if reset else json.loads(json.dumps(current))
    patches = list(page.get("messages", [])) + [
        e["data"]
        for e in page.get("events", [])
        if e["type"] in ("message.created", "notification.updated")
    ]
    for message in patches:
        parsed = Message.from_dict(message)
        previous = result["messages"].get(parsed.message_id, {})
        if int(previous.get("state_version", "0")) <= int(parsed.state_version):
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
    if "unread_count" not in page and ("latest_seq" in page or "read_seq" in page):
        result["unread_count"] = str(int(result["latest_seq"]) - int(result["read_seq"]))
    # 通知水位同样覆盖已加载记录和后续历史补页，不影响单聊。
    for message in result["messages"].values():
        if int(message["conversation_seq"]) <= int(result["read_seq"]):
            message["is_read"] = True
    return result
