import json
from pathlib import Path

import pytest

from polyglot_im import Message

FIXTURE = Path(__file__).resolve().parents[2] / "protocol/fixtures/message.json"


def test_shared_fixture_roundtrip():
    data = json.loads(FIXTURE.read_text())
    message = Message.from_dict(data)
    assert message.conversation_seq == "9007199254740993"
    assert message.to_dict() == data


@pytest.mark.parametrize("value", ["-1", "0", "01", "1.5", 42])
def test_invalid_sequence(value):
    data = json.loads(FIXTURE.read_text())
    data["conversation_seq"] = value
    with pytest.raises(ValueError):
        Message.from_dict(data)


def test_unknown_message_type_and_payload():
    data = json.loads(FIXTURE.read_text())
    data["type"] = "custom.future"
    data["payload"] = {"future": {"nested": [1, 2]}}
    data["future_envelope_field"] = True
    assert Message.from_dict(data).payload == data["payload"]


def test_reject_future_schema():
    data = json.loads(FIXTURE.read_text())
    data["schema_version"] = 2
    with pytest.raises(ValueError):
        Message.from_dict(data)


def test_direct_unicode_fixture_and_revisioned_cache():
    from polyglot_im.client import apply_page, empty_cache

    data = json.loads(FIXTURE.with_name("direct-message.json").read_text())
    assert Message.from_dict(data).to_dict() == data
    cid = data["conversation_id"]
    patch = {
        "conversation_id": cid,
        "revision": "3",
        "read_seq": "2",
        "unread_count": "0",
        "messages": [data],
    }
    state = apply_page(empty_cache(), {"events": [{"type": "conversation.updated", "data": patch}]})
    stale = {**patch, "revision": "1", "read_seq": "0", "unread_count": "1", "messages": []}
    state = apply_page(state, {"conversations": [stale]})
    assert state["conversations"][cid]["read_seq"] == "2"
    assert state["conversations"][cid]["messages"][data["message_id"]] == data
    assert state["messages"] == {}
    assert state["cursor"] is None
    reset = apply_page(state, {"conversations": [patch]}, reset=True)
    assert reset["conversations"][cid]["revision"] == "3"
