"""Checkpoint deserialization must not run code named by stored bytes.

Anyone who can write the project's store DB controls the checkpoint bytes, so
these tests plant msgpack constructor payloads and read them back through the
App's real ``MongoDBSaver``. Only the MongoDB client is faked.
"""

from __future__ import annotations

import builtins
import json
import logging
from collections.abc import Iterator
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Annotated, Any, NotRequired, TypedDict
from unittest.mock import MagicMock, patch

import ormsgpack
import pytest
from agent_engine_runner_shared import RuntimeMode
from langchain_core.messages import AIMessage, AnyMessage, HumanMessage, ToolMessage
from langchain_core.runnables import RunnableConfig
from langgraph.checkpoint.memory import InMemorySaver
from langgraph.graph import START, StateGraph
from langgraph.graph.message import add_messages
from langgraph.types import Command, interrupt

from agent_engine_sdk_langgraph import App
from agent_engine_sdk_langgraph.query import LangGraphQueryPlugin

_PWNED = "_checkpoint_payload_executed"
_EXEC_PAYLOAD = ormsgpack.packb(
    ormsgpack.Ext(
        0,  # EXT_CONSTRUCTOR_SINGLE_ARG: (module, name, arg)
        ormsgpack.packb(
            ("builtins", "exec", f"import builtins; builtins.{_PWNED} = True")
        ),
    )
)


class _FakeCollection:
    def __init__(self) -> None:
        self.docs: list[dict[str, Any]] = []
        self.aggregate_result: list[dict[str, Any]] = []

    def list_indexes(self) -> list[dict[str, Any]]:
        return []

    def create_index(self, *_args: Any, **_kwargs: Any) -> None:
        return None

    def find(self, query: dict[str, Any], *_args: Any, **_kwargs: Any) -> list:
        return [
            doc
            for doc in self.docs
            if all(doc.get(key) == value for key, value in query.items())
        ]

    def aggregate(self, *_args: Any, **_kwargs: Any) -> list[dict[str, Any]]:
        return list(self.aggregate_result)


class _FakeDatabase:
    def __init__(self) -> None:
        self._collections: dict[str, _FakeCollection] = {}

    def __getitem__(self, name: str) -> _FakeCollection:
        return self._collections.setdefault(name, _FakeCollection())


class _FakeMongoClient:
    def __init__(self, *_args: Any, **_kwargs: Any) -> None:
        self._databases: dict[str, _FakeDatabase] = {}

    def __getitem__(self, name: str) -> _FakeDatabase:
        return self._databases.setdefault(name, _FakeDatabase())

    def close(self) -> None:
        return None


@pytest.fixture(autouse=True)
def _clear_exec_sentinel() -> Iterator[None]:
    yield
    if hasattr(builtins, _PWNED):
        delattr(builtins, _PWNED)


@pytest.fixture(autouse=True)
def _checkpoint_db(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("CHECKPOINT_DB_NAME", "mdb_store_proj1")


def _platform_checkpointer(app: App) -> Any:
    runtime = MagicMock()
    runtime.mode = RuntimeMode.AER
    runtime.mongodb_uri = "mongodb://fake:27017"
    app._runtime = runtime
    with patch("pymongo.MongoClient", _FakeMongoClient):
        checkpointer = app.checkpointer()
    assert checkpointer is not None
    return checkpointer


def test_resuming_a_poisoned_checkpoint_does_not_execute_the_payload() -> None:
    checkpointer = _platform_checkpointer(App(app_name="Victim Agent"))
    checkpointer.native.checkpoint_collection.docs.append(
        {
            "thread_id": "session-1:workspace-1",
            "checkpoint_ns": "",
            "checkpoint_id": "checkpoint-1",
            "parent_checkpoint_id": None,
            "type": "msgpack",
            "checkpoint": _EXEC_PAYLOAD,
            "metadata": {},
        }
    )

    checkpointer.get_tuple(
        {"configurable": {"thread_id": "session-1:workspace-1", "checkpoint_ns": ""}}
    )

    assert not hasattr(builtins, _PWNED)


def _json_payloads(target: Path) -> dict[str, Any]:
    return {
        # lc:1 goes to langchain-core's reviver, which ignores the msgpack allowlist.
        "lc1-prompt-template": {
            "lc": 1,
            "type": "constructor",
            "id": ["langchain", "prompts", "prompt", "PromptTemplate"],
            "kwargs": {"template": "{x}", "input_variables": ["x"]},
        },
        # langgraph-checkpoint 4.1.0 dispatches `method` on safe types
        # (GHSA-fjqc-hq36-qh5p).
        "lc2-path-write-text": {
            "lc": 2,
            "type": "constructor",
            "id": ["pathlib", "Path"],
            "method": "write_text",
            "args": [
                {
                    "lc": 2,
                    "type": "constructor",
                    "id": ["pathlib", "Path"],
                    "args": [str(target)],
                },
                "pwned",
            ],
        },
    }


@pytest.mark.parametrize("payload", ["lc1-prompt-template", "lc2-path-write-text"])
def test_resuming_a_json_tagged_checkpoint_is_refused(
    payload: str, tmp_path: Path
) -> None:
    target = tmp_path / "pwned"
    checkpointer = _platform_checkpointer(App(app_name="Victim Agent"))
    checkpointer.native.checkpoint_collection.docs.append(
        {
            "thread_id": "session-1:workspace-1",
            "checkpoint_ns": "",
            "checkpoint_id": "checkpoint-1",
            "parent_checkpoint_id": None,
            "type": "json",
            "checkpoint": json.dumps(_json_payloads(target)[payload]).encode(),
            "metadata": {},
        }
    )

    with pytest.raises(ValueError, match="'json' checkpoint payload"):
        checkpointer.get_tuple(
            {
                "configurable": {
                    "thread_id": "session-1:workspace-1",
                    "checkpoint_ns": "",
                }
            }
        )

    assert not target.exists()


@pytest.mark.anyio
async def test_listing_sessions_over_a_poisoned_write_does_not_execute_the_payload(
    tmp_path: Path,
) -> None:
    target = tmp_path / "pwned"
    native = _platform_checkpointer(App(app_name="Victim Agent")).native
    _, human_write = native.serde.dumps_typed([HumanMessage(content="hello")])
    now = datetime(2026, 10, 2, tzinfo=UTC)
    native.checkpoint_collection.aggregate_result = [
        {"_id": "session-a", "latest_ts": now, "first_ts": now}
    ]
    native.writes_collection.aggregate_result = [
        {
            "_id": "session-a",
            "writes": [
                {"type": "msgpack", "value": _EXEC_PAYLOAD},
                *(
                    {"type": "json", "value": json.dumps(payload).encode()}
                    for payload in _json_payloads(target).values()
                ),
                {"type": "msgpack", "value": human_write},
            ],
        }
    ]

    # Reads through the native saver serde, as the GET /query/sessions plugin does.
    response = await LangGraphQueryPlugin(native).get_summaries_for_sessions(
        ["session-a"]
    )

    assert not hasattr(builtins, _PWNED)
    assert not target.exists()
    assert [s.first_message_preview for s in response.sessions] == ["hello"]


class _ChatState(TypedDict):
    messages: Annotated[list[AnyMessage], add_messages]
    asked_at: NotRequired[datetime]


def test_sdk_serializer_keeps_messages_and_interrupts_across_a_hitl_resume() -> None:
    native = _platform_checkpointer(App(app_name="Agent")).native
    asked_at = datetime(2026, 10, 2, 12, 30, tzinfo=UTC)

    def call_tool(state: _ChatState) -> dict[str, Any]:
        return {
            "asked_at": asked_at,
            "messages": [
                AIMessage(
                    content="",
                    tool_calls=[{"id": "call-1", "name": "refund", "args": {"n": 1}}],
                ),
                ToolMessage(
                    content="queued", tool_call_id="call-1", artifact={"ticket": 7}
                ),
            ],
        }

    def ask_human(state: _ChatState) -> dict[str, Any]:
        decision = interrupt({"question": "approve refund?"})
        return {"messages": [AIMessage(content=f"refund {decision}")]}

    builder = StateGraph(_ChatState)
    builder.add_node("call_tool", call_tool)
    builder.add_node("ask_human", ask_human)
    builder.add_edge(START, "call_tool")
    builder.add_edge("call_tool", "ask_human")
    graph = builder.compile(checkpointer=InMemorySaver(serde=native.serde))
    config: RunnableConfig = {"configurable": {"thread_id": "session-1:workspace-1"}}

    graph.invoke({"messages": [HumanMessage(content="refund me")]}, config)
    paused = graph.get_state(config)
    assert [i.value for i in paused.interrupts] == [{"question": "approve refund?"}]

    graph.invoke(Command(resume="approved"), config)

    final = graph.get_state(config).values
    assert final["asked_at"] == asked_at
    human, call, result, reply = final["messages"]
    assert isinstance(human, HumanMessage)
    assert isinstance(call, AIMessage)
    assert call.tool_calls[0]["args"] == {"n": 1}
    assert isinstance(result, ToolMessage)
    assert result.artifact == {"ticket": 7}
    assert isinstance(reply, AIMessage)
    assert reply.content == "refund approved"


# One class per case: LangGraph logs a blocked type only once per process.
@dataclass
class _AllowedTicket:
    number: int


@dataclass
class _AllowedByNameTicket:
    number: int


_blocked_constructions: list[int] = []


@dataclass
class _UnlistedTicket:
    number: int

    def __post_init__(self) -> None:
        _blocked_constructions.append(self.number)


def _round_trip(app: App, value: Any) -> Any:
    serde = _platform_checkpointer(app).native.serde
    return serde.loads_typed(serde.dumps_typed(value))


@pytest.mark.parametrize(
    ("allowed", "ticket_type"),
    [
        ([_AllowedTicket], _AllowedTicket),
        ([(__name__, "_AllowedByNameTicket")], _AllowedByNameTicket),
    ],
    ids=["class", "module-name-tuple"],
)
def test_agent_author_can_allow_a_custom_checkpoint_type(
    allowed: list[Any], ticket_type: type
) -> None:
    app = App(app_name="Agent", checkpoint_allowed_types=allowed)

    assert _round_trip(app, {"ticket": ticket_type(7)}) == {"ticket": ticket_type(7)}


def test_unlisted_custom_type_comes_back_as_raw_data_without_being_constructed(
    caplog: pytest.LogCaptureFixture,
) -> None:
    serde = _platform_checkpointer(App(app_name="Agent")).native.serde
    stored = serde.dumps_typed({"ticket": _UnlistedTicket(7)})
    _blocked_constructions.clear()

    # App's logging setup replaces the root handlers, caplog's included.
    root = logging.getLogger()
    root.addHandler(caplog.handler)
    try:
        with caplog.at_level(logging.WARNING):
            loaded = serde.loads_typed(stored)
    finally:
        root.removeHandler(caplog.handler)

    assert loaded == {"ticket": {"number": 7}}
    assert _blocked_constructions == []
    assert any(
        "Blocked deserialization" in record.getMessage()
        and "_UnlistedTicket" in record.getMessage()
        for record in caplog.records
    )


@pytest.mark.parametrize(
    "allowed",
    [True, "my_pkg.Ticket", [("my_pkg",)], [("my_pkg", 3)], [42]],
    ids=["true-reopens-permissive", "bare-string", "1-tuple", "non-str-name", "int"],
)
def test_malformed_checkpoint_allowlist_is_rejected_at_construction(
    allowed: Any,
) -> None:
    with pytest.raises(TypeError, match="checkpoint_allowed_types"):
        App(app_name="Agent", checkpoint_allowed_types=allowed)
