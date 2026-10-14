"""ADK cancelled-history capture wiring."""

from __future__ import annotations

import asyncio
from contextlib import suppress
from typing import Any
from unittest.mock import MagicMock, patch

import pytest
from agent_engine_sdk import AgentInput, RequestContext
from google.adk.events import Event
from google.adk.events.event import NodeInfo
from google.adk.sessions import Session
from google.genai import types

from agent_engine_runner_shared.generated.workflow.v1.activity_pb2 import (
    ACTIVITY_OUTCOME_KIND_COMPLETED,
    ACTIVITY_OUTCOME_KIND_SUSPENDED,
    ActivityOutcome,
    StepActivityEntry,
)
from agent_engine_runner_shared.generated.workflow.v1.common_pb2 import WorkflowIdentity
from agent_engine_runner_shared.generated.workflow.v1.runtime_pb2 import AttemptContext
from agent_engine_runner_shared.generated.workflow.v1.state_pb2 import (
    MESSAGE_ROLE_USER,
    StateSnapshot,
    WorkflowMessage,
)
from agent_engine_runner_shared.workflow import (
    CancelledHistory,
    attempt_context_scope,
    current_cancelled_history,
)
from agent_engine_runner_shared.workflow.client import FinalizedStepEntries
from agent_engine_runner_shared.workflow.protojson import json_to_proto_value
from agent_engine_runner_shared.workflow.settlement import settle_execution
from agent_engine_sdk_adk.agent import ADKBaseAgent, UnsupportedDurableADKError
from agent_engine_sdk_adk.suspend import extract_suspends
from agent_engine_sdk_adk.workflow import (
    CompletedWaitFrontier,
    FreshWaitFrontier,
    resolve_wait_frontier,
)

_SETTLED_HASH = "accepted-state-hash"


def _attempt(*, fencing_token: int = 7) -> AttemptContext:
    return AttemptContext(
        attempt_id="attempt-1",
        fencing_token=fencing_token,
        workflow_identity=WorkflowIdentity(
            session_id="session-1", execution_id="execution-1"
        ),
    )


def _context() -> RequestContext:
    return RequestContext(session_id="session-1", user_id="user-1")


def _agent() -> ADKBaseAgent:
    return ADKBaseAgent(MagicMock(), runner=MagicMock())


def _opener() -> WorkflowMessage:
    return WorkflowMessage(id="durable-input:execution-1", role=MESSAGE_ROLE_USER)


def _request_input_event() -> Event:
    return Event(
        invocation_id="invocation-1",
        author="agent",
        node_info=NodeInfo(path="agent@1"),
        long_running_tool_ids=["manager_approval"],
        content=types.Content(
            role="model",
            parts=[
                types.Part(
                    function_call=types.FunctionCall(
                        id="manager_approval",
                        name="adk_request_input",
                        args={"message": "review", "payload": {}},
                    )
                )
            ],
        ),
    )


def _user_event() -> Event:
    return Event(
        id="user-event-1",
        invocation_id="invocation-1",
        author="user",
        content=types.Content(role="user", parts=[types.Part.from_text(text="prompt")]),
    )


def _session() -> Session:
    return Session(
        id="session-1", app_name="app", user_id="user-1", events=[_user_event()]
    )


class _StepClient:
    """Finalize client reporting each step frontier with one outcome kind."""

    def __init__(self, outcome_kind: int = ACTIVITY_OUTCOME_KIND_COMPLETED) -> None:
        self._outcome_kind = outcome_kind
        self.commands: list[Any] = []

    async def __aenter__(self) -> _StepClient:
        return self

    async def __aexit__(self, *_args: Any) -> None:
        return None

    async def finalize_step(self, command: Any) -> FinalizedStepEntries:
        self.commands.append(command)
        entries = [
            StepActivityEntry(
                position=suspension.position,
                outcome=ActivityOutcome(
                    workflow_identity=WorkflowIdentity(
                        session_id="session-1", execution_id="execution-1"
                    ),
                    activity_id="activity-1",
                    attempt_id="attempt-1",
                    fencing_token=7,
                    outcome_kind=self._outcome_kind,
                ),
            )
            for suspension in command.suspensions
        ]
        return FinalizedStepEntries(entries, _SETTLED_HASH)

    async def complete_execution(self, _command: Any) -> None:
        return None


def test_cancelled_history_scope_sets_the_request_opener_and_clears_on_exit() -> None:
    agent = _agent()
    with attempt_context_scope(_attempt()):
        assert current_cancelled_history.get() is None
        with agent.cancelled_history_scope(
            _context(), AgentInput(payload={"message": "hello"})
        ) as history:
            assert current_cancelled_history.get() is history
            opener_id, messages, ordinal, source_hash = history.capture()
            assert opener_id == "durable-input:execution-1"
            assert ordinal == 0
            assert source_hash is None
            assert [
                (message.id, message.role, message.content.string_value)
                for message in messages
            ] == [("durable-input:execution-1", MESSAGE_ROLE_USER, "hello")]
        assert current_cancelled_history.get() is None


def test_cancelled_history_scope_requires_an_attempt() -> None:
    agent = _agent()
    with pytest.raises(UnsupportedDurableADKError):
        with agent.cancelled_history_scope(
            _context(), AgentInput(payload={"message": "hello"})
        ):
            pass


@pytest.mark.asyncio
async def test_resolve_wait_frontier_acknowledges_the_settled_step() -> None:
    attempt = _attempt()
    history = CancelledHistory(attempt, _opener())
    client = _StepClient()
    suspend = extract_suspends(_request_input_event())[0]

    token = current_cancelled_history.set(history)
    try:
        with (
            attempt_context_scope(attempt),
            patch(
                "agent_engine_sdk_adk.workflow.get_current_oe_url", lambda: "http://oe"
            ),
            patch(
                "agent_engine_sdk_adk.workflow.AsyncWorkflowClient",
                lambda _url: client,
            ),
        ):
            resolution = await resolve_wait_frontier(attempt, _session(), (suspend,))
    finally:
        current_cancelled_history.reset(token)

    assert isinstance(resolution, CompletedWaitFrontier)
    opener_id, _messages, ordinal, source_hash = history.capture()
    # The settled step's accepted root is retained, at the finalized command's
    # exact ordinal, with the opener re-selected from the ADK snapshot.
    assert source_hash == _SETTLED_HASH
    assert ordinal == client.commands[-1].step_ordinal
    assert ordinal >= 1
    assert opener_id == "user-event-1"


@pytest.mark.asyncio
async def test_resolve_wait_frontier_suspension_leaves_capture_opener_only() -> None:
    attempt = _attempt()
    history = CancelledHistory(attempt, _opener())
    client = _StepClient(outcome_kind=ACTIVITY_OUTCOME_KIND_SUSPENDED)
    suspend = extract_suspends(_request_input_event())[0]

    token = current_cancelled_history.set(history)
    try:
        with (
            attempt_context_scope(attempt),
            patch(
                "agent_engine_sdk_adk.workflow.get_current_oe_url", lambda: "http://oe"
            ),
            patch(
                "agent_engine_sdk_adk.workflow.AsyncWorkflowClient",
                lambda _url: client,
            ),
        ):
            resolution = await resolve_wait_frontier(attempt, _session(), (suspend,))
    finally:
        current_cancelled_history.reset(token)

    assert isinstance(resolution, FreshWaitFrontier)
    # A pending frontier acknowledges nothing: the capture stays opener-only.
    opener_id, messages, ordinal, source_hash = history.capture()
    assert opener_id == "durable-input:execution-1"
    assert len(messages) == 1
    assert ordinal == 0
    assert source_hash is None


@pytest.mark.asyncio
async def test_resolve_wait_frontier_ignores_a_different_attempt() -> None:
    attempt = _attempt(fencing_token=7)
    other = CancelledHistory(_attempt(fencing_token=99), _opener())
    client = _StepClient()
    suspend = extract_suspends(_request_input_event())[0]

    token = current_cancelled_history.set(other)
    try:
        with (
            attempt_context_scope(attempt),
            patch(
                "agent_engine_sdk_adk.workflow.get_current_oe_url", lambda: "http://oe"
            ),
            patch(
                "agent_engine_sdk_adk.workflow.AsyncWorkflowClient",
                lambda _url: client,
            ),
        ):
            await resolve_wait_frontier(attempt, _session(), (suspend,))
    finally:
        current_cancelled_history.reset(token)

    # A capture owned by another fence is never updated.
    assert other.capture()[2] == 0
    assert other.capture()[3] is None


@pytest.mark.asyncio
async def test_settle_execution_retains_the_terminal_root_before_completion() -> None:
    attempt = _attempt()
    history = CancelledHistory(attempt, _opener())
    state = StateSnapshot(
        messages=[
            WorkflowMessage(
                id="user-event-1",
                role=MESSAGE_ROLE_USER,
                content=json_to_proto_value("prompt"),
            )
        ]
    )
    entered = asyncio.Event()

    class _BlockingCompletion(_StepClient):
        async def complete_execution(self, _command: Any) -> None:
            entered.set()
            await asyncio.Event().wait()  # the Stop lands in this window

    client = _BlockingCompletion()
    token = current_cancelled_history.set(history)
    try:
        with (
            attempt_context_scope(attempt),
            patch(
                "agent_engine_runner_shared.workflow.settlement.get_current_oe_url",
                lambda: "http://oe",
            ),
            patch(
                "agent_engine_runner_shared.workflow.settlement.AsyncWorkflowClient",
                lambda _url: client,
            ),
        ):
            task = asyncio.create_task(settle_execution(attempt, state))
            await asyncio.wait_for(entered.wait(), timeout=5)
            task.cancel()
            with suppress(asyncio.CancelledError):
                await task
    finally:
        current_cancelled_history.reset(token)

    # The terminal root was retained before the completion await, so the Stop
    # that tears down that await still reports the last acknowledged step.
    opener_id, _messages, ordinal, source_hash = history.capture()
    assert source_hash == _SETTLED_HASH
    assert ordinal == client.commands[-1].step_ordinal
    assert opener_id == "user-event-1"
