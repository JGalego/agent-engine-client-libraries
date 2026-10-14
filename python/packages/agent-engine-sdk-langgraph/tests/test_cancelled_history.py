from __future__ import annotations

import asyncio
from typing import Any
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from agent_engine_runner_shared.context import current_oe_url
from agent_engine_runner_shared.generated.workflow.v1.common_pb2 import WorkflowIdentity
from agent_engine_runner_shared.generated.workflow.v1.runtime_pb2 import AttemptContext
from agent_engine_runner_shared.generated.workflow.v1.state_pb2 import (
    MESSAGE_ROLE_ASSISTANT,
    MESSAGE_ROLE_SYSTEM,
    MESSAGE_ROLE_USER,
    StateSnapshot,
    WorkflowMessage,
)
from agent_engine_runner_shared.workflow import attempt_context_scope
from agent_engine_runner_shared.workflow.client import FinalizedStepEntries
from agent_engine_runner_shared.workflow.protojson import json_to_proto_value
from agent_engine_sdk import AgentInput, RequestContext
from langchain_core.messages import HumanMessage
from langchain_core.runnables import RunnableConfig
from langgraph.checkpoint.base import empty_checkpoint
from langgraph.checkpoint.memory import InMemorySaver

from agent_engine_sdk_langgraph.agent import LangGraphBaseAgent
from agent_engine_runner_shared.workflow import (
    CancelledHistory,
    current_cancelled_history,
)
from agent_engine_sdk_langgraph.platform_checkpointer import PlatformCheckpointer


def attempt(name: str = "one") -> AttemptContext:
    return AttemptContext(
        attempt_id=name,
        fencing_token=1,
        workflow_identity=WorkflowIdentity(execution_id="execution"),
    )


def _upper_prepare(value: AgentInput, _ctx: RequestContext) -> dict[str, Any]:
    payload = value.payload if isinstance(value.payload, dict) else {}
    return {"messages": [HumanMessage(content=str(payload.get("message", "")).upper())]}


def test_capture_is_immutable_and_normalized_input_replaces_fallback() -> None:
    opener = WorkflowMessage(id="request", role=MESSAGE_ROLE_USER)
    history = CancelledHistory(attempt(), opener)
    opener.id = "mutated"
    assert history.capture()[0] == "request"
    normalized = StateSnapshot(
        messages=[WorkflowMessage(id="normalized", role=MESSAGE_ROLE_USER)]
    )
    history.normalized_input(normalized)
    history.acknowledge(normalized, 2, "original-hash")
    normalized.messages[0].id = "changed"
    history.acknowledge(normalized, 1, "stale")
    captured = history.capture()
    assert (captured[0], captured[2], captured[3]) == ("normalized", 2, "original-hash")
    assert [message.id for message in captured[1]] == ["normalized"]
    captured[1][0].id = "caller-mutated"
    assert history.capture()[1][0].id == "normalized"


@pytest.mark.parametrize("prompt", ["original", "changed"])
def test_normalized_opener_is_the_newest_user_regardless_of_content(
    prompt: str,
) -> None:
    history = CancelledHistory(
        attempt(),
        WorkflowMessage(
            id="request",
            role=MESSAGE_ROLE_USER,
            content=json_to_proto_value("original"),
        ),
    )
    history.normalized_input(
        StateSnapshot(
            messages=[
                WorkflowMessage(
                    id="prior",
                    role=MESSAGE_ROLE_USER,
                    content=json_to_proto_value("prior"),
                ),
                WorkflowMessage(
                    id="current",
                    role=MESSAGE_ROLE_USER,
                    content=json_to_proto_value(prompt),
                ),
            ]
        )
    )
    opener_id, messages, ordinal, source_hash = history.capture()
    assert opener_id == "current"
    # No acknowledged step: the turn is only its opener.
    assert [message.id for message in messages] == ["current"]
    assert ordinal == 0
    assert source_hash is None


def test_normalized_opener_selects_the_current_turn_for_repeated_prompts() -> None:
    # A hook that carries conversation history may repeat an earlier prompt
    # verbatim; the newest USER is the current turn's opener, not the first.
    history = CancelledHistory(
        attempt(),
        WorkflowMessage(
            id="request",
            role=MESSAGE_ROLE_USER,
            content=json_to_proto_value("hello"),
        ),
    )
    history.normalized_input(
        StateSnapshot(
            messages=[
                WorkflowMessage(
                    id="u1",
                    role=MESSAGE_ROLE_USER,
                    content=json_to_proto_value("hello"),
                ),
                WorkflowMessage(
                    id="u2",
                    role=MESSAGE_ROLE_USER,
                    content=json_to_proto_value("hello"),
                ),
            ]
        )
    )
    opener_id, messages, _, _ = history.capture()
    assert opener_id == "u2"
    assert [message.id for message in messages] == ["u2"]


def test_normalized_opener_ignores_a_historical_content_match() -> None:
    # A hook that rewrites the current prompt ('hello' -> 'HELLO') while carrying
    # a historical 'hello' must still pick the current opener, not the historical.
    history = CancelledHistory(
        attempt(),
        WorkflowMessage(
            id="request", role=MESSAGE_ROLE_USER, content=json_to_proto_value("hello")
        ),
    )
    history.normalized_input(
        StateSnapshot(
            messages=[
                WorkflowMessage(
                    id="u1",
                    role=MESSAGE_ROLE_USER,
                    content=json_to_proto_value("hello"),
                ),
                WorkflowMessage(
                    id="u2",
                    role=MESSAGE_ROLE_USER,
                    content=json_to_proto_value("HELLO"),
                ),
            ]
        )
    )
    opener_id, messages, _, _ = history.capture()
    assert opener_id == "u2"
    assert [message.id for message in messages] == ["u2"]


def test_unacknowledged_capture_is_opener_only_for_the_replacement_fallback() -> None:
    # OE's replacement-before-replay fallback requires an opener-only report, so a
    # multi-message hook input must project to just the current USER opener.
    history = CancelledHistory(
        attempt(),
        WorkflowMessage(
            id="request", role=MESSAGE_ROLE_USER, content=json_to_proto_value("current")
        ),
    )
    history.normalized_input(
        StateSnapshot(
            messages=[
                WorkflowMessage(
                    id="h1",
                    role=MESSAGE_ROLE_USER,
                    content=json_to_proto_value("history one"),
                ),
                WorkflowMessage(
                    id="h2",
                    role=MESSAGE_ROLE_ASSISTANT,
                    content=json_to_proto_value("history two"),
                ),
                WorkflowMessage(
                    id="u2",
                    role=MESSAGE_ROLE_USER,
                    content=json_to_proto_value("current"),
                ),
            ]
        )
    )
    opener_id, messages, ordinal, source_hash = history.capture()
    assert (opener_id, ordinal, source_hash) == ("u2", 0, None)
    assert [message.id for message in messages] == ["u2"]


def test_system_only_input_keeps_the_request_opener() -> None:
    # A hook that supplies no USER message must not adopt a non-USER message as
    # the stopped opener.
    history = CancelledHistory(
        attempt(),
        WorkflowMessage(
            id="request",
            role=MESSAGE_ROLE_USER,
            content=json_to_proto_value("hello"),
        ),
    )
    history.normalized_input(
        StateSnapshot(messages=[WorkflowMessage(id="sys", role=MESSAGE_ROLE_SYSTEM)])
    )
    opener_id, messages, _, _ = history.capture()
    assert opener_id == "request"
    assert [message.id for message in messages] == ["request"]


def test_acknowledged_snapshot_that_drops_the_opener_restores_it() -> None:
    # A completed step may remove the opener; the report must still carry the
    # opener (OE rejects a transcript whose opener id has no matching USER) and
    # must not mutate the retained snapshot or its ACK hash.
    history = CancelledHistory(
        attempt(),
        WorkflowMessage(
            id="request",
            role=MESSAGE_ROLE_USER,
            content=json_to_proto_value("hello"),
        ),
    )
    history.acknowledge(
        StateSnapshot(
            messages=[WorkflowMessage(id="assistant", role=MESSAGE_ROLE_ASSISTANT)]
        ),
        3,
        "hash-3",
    )
    opener_id, messages, ordinal, source_hash = history.capture()
    assert opener_id == "request"
    assert [message.id for message in messages] == ["request", "assistant"]
    assert (ordinal, source_hash) == (3, "hash-3")
    # A second capture is identical: the retained snapshot is untouched.
    assert [message.id for message in history.capture()[1]] == ["request", "assistant"]


@pytest.mark.anyio
@pytest.mark.parametrize("sync", [False, True])
async def test_only_successful_root_acknowledgement_is_captured(sync: bool) -> None:
    context = attempt()
    history = CancelledHistory(
        context, WorkflowMessage(id="opener", role=MESSAGE_ROLE_USER)
    )
    token = current_cancelled_history.set(history)
    oe_token = current_oe_url.set("http://localhost")
    checkpoint = empty_checkpoint()
    checkpoint["channel_values"] = {
        "messages": [HumanMessage(content="prompt", id="opener")]
    }
    saver = PlatformCheckpointer(native=None)
    client = MagicMock() if sync else AsyncMock()
    client.finalize_step.return_value = FinalizedStepEntries([], "accepted-state-hash")
    path = "WorkflowClient" if sync else "AsyncWorkflowClient"
    try:
        with (
            attempt_context_scope(context),
            patch(
                f"agent_engine_sdk_langgraph.platform_checkpointer.{path}"
            ) as factory,
        ):
            if sync:
                factory.return_value.__enter__.return_value = client
            else:
                factory.return_value.__aenter__.return_value = client
            config: RunnableConfig = {
                "configurable": {"thread_id": "thread", "checkpoint_ns": ""}
            }
            if sync:
                saver.put(config, checkpoint, {"source": "input", "step": -1}, {})
            else:
                await saver.aput(
                    config, checkpoint, {"source": "input", "step": -1}, {}
                )
            assert history.capture()[2] == 0
            client.finalize_step.side_effect = RuntimeError("rejected")
            with pytest.raises(RuntimeError, match="rejected"):
                if sync:
                    saver.put(config, checkpoint, {"source": "loop", "step": 0}, {})
                else:
                    await saver.aput(
                        config, checkpoint, {"source": "loop", "step": 0}, {}
                    )
            assert history.capture()[2] == 0
            client.finalize_step.side_effect = None
            if sync:
                saver.put(config, checkpoint, {"source": "loop", "step": 0}, {})
            else:
                await saver.aput(config, checkpoint, {"source": "loop", "step": 0}, {})
            assert history.capture()[2] == 1
            original_hash = history.capture()[3]
            assert original_hash == "accepted-state-hash"
            checkpoint["channel_values"]["messages"][0].content = "unacknowledged"
            assert history.capture()[1][0].content.string_value == "prompt"
            assert history.capture()[3] == original_hash
    finally:
        current_cancelled_history.reset(token)
        current_oe_url.reset(oe_token)


@pytest.mark.anyio
async def test_shared_agent_scopes_isolate_attempts_and_hook_opener() -> None:
    graph = MagicMock()
    graph.checkpointer = PlatformCheckpointer(native=InMemorySaver())
    agent = LangGraphBaseAgent(graph, prepare_input=_upper_prepare)
    ready = asyncio.Event()
    captures = []

    async def run(name: str) -> None:
        with attempt_context_scope(attempt(name)):
            ctx = RequestContext(execution_id="execution", session_id="session")
            value = AgentInput(payload={"message": name})
            with agent.cancelled_history_scope(ctx, value) as history:
                assert history.capture()[0] == "durable-input:execution"
                session = agent._execution_session()
                session.build_graph_input(ctx, value)
                checkpoint = empty_checkpoint()
                checkpoint["channel_values"] = {"value": name}
                config = {"configurable": {"thread_id": "session", "checkpoint_ns": ""}}
                graph.checkpointer.put(
                    config, checkpoint, {"source": "input", "step": -1}, {}
                )
                session.release_scratch()
                assert graph.checkpointer.get_tuple(config) is not None
                ready.set()
                await ready.wait()
                await asyncio.sleep(0)
                captures.append(history.capture())
            assert current_cancelled_history.get() is None
            assert graph.checkpointer.get_tuple(config) is None

    await asyncio.gather(run("one"), run("two"))
    assert {capture[1][0].content.string_value for capture in captures} == {
        "ONE",
        "TWO",
    }
    assert all(capture[0] == "durable-input:execution:0" for capture in captures)
    assert all(len(capture[1]) == 1 for capture in captures)


def test_hitl_capture_uses_original_execution_input_not_resume_answer() -> None:
    graph = MagicMock()
    graph.checkpointer = PlatformCheckpointer(native=None)
    observed = []

    def prepare(value, ctx):
        observed.append((value.payload["message"], ctx.resume))
        return {"messages": [HumanMessage(content=value.payload["message"])]}

    agent = LangGraphBaseAgent(graph, prepare_input=prepare)
    context = attempt()
    context.replay_mode = True
    ctx = RequestContext(
        execution_id="execution",
        session_id="session",
        resume=True,
        resume_data={"activity": "answer"},
    )
    value = AgentInput(payload={"message": "original opener"})
    with (
        attempt_context_scope(context),
        agent.cancelled_history_scope(ctx, value) as history,
    ):
        agent._execution_session().build_graph_input(ctx, value)
        assert observed == [("original opener", False)]
        assert history.capture()[1][0].content.string_value == "original opener"
        assert history.capture()[2] == 0
