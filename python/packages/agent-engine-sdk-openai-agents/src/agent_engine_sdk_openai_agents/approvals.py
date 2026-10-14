"""Record OpenAI Agents approval interruptions as OE wait frontiers.

A tool with ``needs_approval`` stops the native run with a ``ToolApprovalItem``
for each call that needs it, whether the turn's own agent made the call or an
agent it uses as a tool did. The adapter records those waits with OE as one
suspension frontier, each at the operation path of the run that made the call:
the first time, OE creates the waits and the turn suspends; once the caller
answers every wait through ``/invoke`` with ``resume_map``, replay reaches the
same frontier and OE returns the recorded answers, which the adapter applies
through ``RunState``.

A model response OE holds for a guardrail review stops the run the same way,
on the review tool, and its wait joins the same frontier. OE recognizes that
wait as the review's and records the reviewer's decision when it is answered.
"""

from __future__ import annotations

import asyncio
import json
from collections.abc import Generator, Mapping, Sequence
from contextlib import ExitStack, contextmanager
from dataclasses import dataclass
from typing import Any, cast

from agent_engine_sdk import StreamEvent
from agents import ToolApprovalItem
from agents.tool import DEFAULT_APPROVAL_REJECTION_MESSAGE
from pydantic import JsonValue

from agent_engine_runner_shared.context import (
    get_current_oe_url,
    get_current_user_id,
    get_current_wrapper,
)
from agent_engine_runner_shared.generated.workflow.v1.activity_pb2 import (
    ACTIVITY_KIND_TOOL,
    ACTIVITY_OUTCOME_KIND_SUSPENDED,
    ActivityCommand,
    ActivityContext,
    ActivityOutcome,
    ActivitySuspension,
    StepSuspensionEntry,
)
from agent_engine_runner_shared.generated.workflow.v1.common_pb2 import (
    WORKFLOW_ERROR_CODE_CONFLICT,
    ActivityPosition,
)
from agent_engine_runner_shared.generated.workflow.v1.runtime_pb2 import AttemptContext
from agent_engine_runner_shared.secure_llm_proxy import (
    GUARDRAIL_REVIEW_WAIT_KEY,
    GuardrailReviewPending,
)
from agent_engine_runner_shared.workflow import AsyncWorkflowClient
from agent_engine_runner_shared.workflow.activity import (
    build_activity_command,
    unwrap_activity_outcome,
)
from agent_engine_runner_shared.workflow.attempt import (
    finalize_current_step_suspensions_command,
)
from agent_engine_runner_shared.workflow.client import WorkflowClientError
from agent_engine_runner_shared.workflow.context import (
    ChildOperationBoundary,
    allocate_activity_ordinal,
    child_operation_boundary_scope,
    current_operation_path,
    record_observed_activity,
)
from agent_engine_runner_shared.workflow.protojson import json_to_proto_struct
from agent_engine_sdk_openai_agents.errors import UnsupportedDurableOpenAIAgentsError
from agent_engine_sdk_openai_agents.review import REVIEW_TOOL_NAME
from agent_engine_sdk_openai_agents.tools import ModelCall

__all__ = [
    "APPROVAL_ANSWER_SCHEMA",
    "ApprovalDecision",
    "PendingApproval",
    "approval_call_id",
    "record_approvals",
    "suspend_event",
]

SUSPENSION_REASON = "tool_approval"
REVIEW_SUSPENSION_REASON = "guardrail_review"

# One answer per pending approval: approve, or reject with an optional
# rejection_message, the SDK's own name for the text the model reads in place
# of the tool's result. An approval carries no message, since the SDK's
# approve has nowhere to put one.
APPROVAL_ANSWER_SCHEMA: dict[str, JsonValue] = {
    "type": "object",
    "required": ["confirmed"],
    "properties": {
        "confirmed": {"type": "boolean"},
        # Not empty: omit it for the SDK's default rejection text.
        "rejection_message": {"type": "string", "minLength": 1},
    },
    "additionalProperties": False,
    "if": {"properties": {"confirmed": {"const": True}}},
    "then": {"not": {"required": ["rejection_message"]}},
}


# OE checks a review's answer against its own copy of this contract and
# records the decision; the adapter never applies one itself.
REVIEW_ANSWER_SCHEMA: dict[str, JsonValue] = {
    "type": "object",
    "required": ["decision"],
    "properties": {
        "decision": {"enum": ["approve", "deny"]},
        "reviewer_notes": {"type": "string"},
    },
    "additionalProperties": False,
}


@dataclass(frozen=True)
class PendingApproval:
    """OE recorded a new wait; the turn suspends on it."""

    call_id: str
    activity_id: str
    value: dict[str, JsonValue]
    answer_schema: dict[str, JsonValue]


@dataclass(frozen=True)
class ApprovalDecision:
    """OE returned the caller's recorded answer for this wait."""

    call_id: str
    activity_id: str
    approved: bool
    rejection_message: str | None


@dataclass(frozen=True)
class _Wait:
    """One pending call of the run, as the wait OE records for it."""

    call_id: str
    # What OE stores for the wait and the caller reads from the suspension.
    value: dict[str, JsonValue]
    answer_schema: dict[str, JsonValue]
    activity_name: str
    # Keyed by the model's call id, or the review's, so every replay reaches
    # the same wait.
    ordinal_key: str
    semantic_input: JsonValue
    reason: str
    # The guardrail review this wait is for, when it is not a tool approval.
    review_id: str | None = None


def _wait(
    item: ToolApprovalItem, reviews: Mapping[str, GuardrailReviewPending]
) -> _Wait:
    raw = cast(Any, item.raw_item)
    call_id = getattr(raw, "call_id", None)
    name = item.tool_name or getattr(raw, "name", None)
    arguments = getattr(raw, "arguments", None)
    if not isinstance(call_id, str) or not call_id or not isinstance(name, str):
        raise UnsupportedDurableOpenAIAgentsError(
            "only function-tool approvals with a call id are supported"
        )
    if name == REVIEW_TOOL_NAME:
        held = reviews.get(call_id)
        if held is None:
            # Only a halt OE recorded this attempt stops the run for a review.
            raise UnsupportedDurableOpenAIAgentsError(
                "a guardrail review wait has no recorded halt"
            )
        review_id = held.review.review_id
        return _Wait(
            call_id=call_id,
            value={
                GUARDRAIL_REVIEW_WAIT_KEY: cast(
                    JsonValue, held.review.model_dump(mode="json")
                )
            },
            answer_schema=REVIEW_ANSWER_SCHEMA,
            activity_name=REVIEW_SUSPENSION_REASON,
            ordinal_key=f"review:{review_id}",
            semantic_input={"kind": REVIEW_SUSPENSION_REASON, "review_id": review_id},
            reason=REVIEW_SUSPENSION_REASON,
            review_id=review_id,
        )
    try:
        args: JsonValue = (
            json.loads(arguments) if isinstance(arguments, str) and arguments else {}
        )
    except json.JSONDecodeError as error:
        raise UnsupportedDurableOpenAIAgentsError(
            f"tool {name!r} approval has arguments that are not JSON"
        ) from error
    tool_call: JsonValue = {"name": name, "args": args, "call_id": call_id}
    return _Wait(
        call_id=call_id,
        value={"tool_call": tool_call, "response_schema": APPROVAL_ANSWER_SCHEMA},
        answer_schema=APPROVAL_ANSWER_SCHEMA,
        activity_name=f"approve:{name}",
        ordinal_key=f"approval:{call_id}",
        semantic_input=tool_call,
        reason=SUSPENSION_REASON,
    )


def _review_decision(wait: _Wait, activity_id: str, answer: object) -> ApprovalDecision:
    named = (
        cast(dict[str, object], answer).get(GUARDRAIL_REVIEW_WAIT_KEY)
        if isinstance(answer, dict)
        else None
    )
    if (
        not isinstance(named, dict)
        or cast(dict[str, object], named).get("review_id") != wait.review_id
        or cast(dict[str, object], named).get("decision") not in ("approve", "deny")
    ):
        raise UnsupportedDurableOpenAIAgentsError(
            "guardrail review answer does not match its recorded halt"
        )
    # Whatever the decision, the review tool runs so the Runner asks the model
    # again: OE answers that request with the response or with the denial.
    return ApprovalDecision(
        call_id=wait.call_id,
        activity_id=activity_id,
        approved=True,
        rejection_message=None,
    )


def _decision(call_id: str, activity_id: str, answer: object) -> ApprovalDecision:
    # The answer is caller input that OE validated against the resume schema;
    # check it again at this sink rather than trusting the transport.
    if not isinstance(answer, dict):
        raise UnsupportedDurableOpenAIAgentsError("approval answer must be an object")
    answer = cast(dict[str, object], answer)
    confirmed = answer.get("confirmed")
    rejection_message = answer.get("rejection_message")
    if (
        set(answer) - {"confirmed", "rejection_message"}
        or not isinstance(confirmed, bool)
        or not (
            rejection_message is None
            or (isinstance(rejection_message, str) and rejection_message)
        )
        or (confirmed and rejection_message is not None)
    ):
        raise UnsupportedDurableOpenAIAgentsError(
            "approval answer must be {confirmed: true} or "
            "{confirmed: false, rejection_message?: non-empty string}"
        )
    return ApprovalDecision(
        call_id=call_id,
        activity_id=activity_id,
        approved=confirmed,
        rejection_message=rejection_message,
    )


async def record_approvals(
    attempt: AttemptContext,
    interruptions: list[ToolApprovalItem],
    calls: Mapping[str, ModelCall],
    reviews: Mapping[str, GuardrailReviewPending],
) -> tuple[PendingApproval, ...] | tuple[ApprovalDecision, ...]:
    """Record the run's approval waits with OE, or return their recorded answers.

    Every pending approval joins one frontier, in the model's call order; OE
    resolves the whole frontier at once, so the waits are either all pending or
    all answered. ``calls`` says which run made each call this turn, and
    ``reviews`` holds the model calls OE halted for a guardrail review.
    """
    if not interruptions:
        raise UnsupportedDurableOpenAIAgentsError("there is no approval to record")
    waits = [_wait(item, reviews) for item in interruptions]
    call_ids = [wait.call_id for wait in waits]
    if len(set(call_ids)) != len(call_ids) or not set(call_ids) <= set(calls):
        raise UnsupportedDurableOpenAIAgentsError(
            "approvals must name distinct calls from the run's input"
        )

    def place(call_id: str) -> tuple[int, tuple[tuple[str, int], ...], int]:
        call = calls[call_id]
        # A nested agent's call sorts with the turn's own call that started
        # its run, then by the run's path: sibling runs in the order OE numbers
        # them, not the order of their opaque call ids.
        started_by = call.boundaries[0].occurrence_key if call.boundaries else call_id
        root = calls.get(started_by)
        if root is None or root.boundaries:
            raise UnsupportedDurableOpenAIAgentsError(
                f"approval call {call_id!r} belongs to an agent run that no "
                "call of this turn started"
            )
        with _run_path(call.boundaries):
            path = current_operation_path().segments
        return root.order, tuple((s.name, s.ordinal) for s in path), call.order

    # The SDK does not promise its interruption order on a resumed run, and a
    # wait's ordinal depends on when it is allocated.
    ordered = sorted(waits, key=lambda wait: place(wait.call_id))
    commands = [
        _wait_command(attempt, wait, calls[wait.call_id].boundaries) for wait in ordered
    ]
    entries: list[StepSuspensionEntry] = []
    for command, wait in zip(commands, ordered, strict=True):
        record_observed_activity(command.position)
        entries.append(
            StepSuspensionEntry(
                position=command.position,
                activity_kind=command.activity_kind,
                activity_name=command.activity_name,
                semantic_input=command.semantic_input,
                suspension=ActivitySuspension(
                    reason=wait.reason,
                    context=json_to_proto_struct({"value": wait.value}),
                ),
            )
        )
    oe_url = get_current_oe_url()
    if not oe_url:
        raise UnsupportedDurableOpenAIAgentsError(
            "OpenAI Agents approvals require the OE callback URL"
        )
    try:
        async with AsyncWorkflowClient(oe_url) as client:
            returned = await client.finalize_step(
                finalize_current_step_suspensions_command(attempt, entries)
            )
    except WorkflowClientError as error:
        if error.code == WORKFLOW_ERROR_CODE_CONFLICT:
            raise UnsupportedDurableOpenAIAgentsError(
                "the approval waits could not be recorded under the current fence"
            ) from error
        raise
    by_position = {_position_key(entry.position): entry.outcome for entry in returned}
    outcomes = [
        by_position.get(_position_key(command.position)) for command in commands
    ]
    if len(returned) != len(commands) or None in outcomes:
        raise UnsupportedDurableOpenAIAgentsError(
            "OE returned an unexpected approval frontier"
        )
    resolved = cast(list[ActivityOutcome], outcomes)
    suspended = [
        outcome.outcome_kind == ACTIVITY_OUTCOME_KIND_SUSPENDED for outcome in resolved
    ]
    if all(suspended):
        return tuple(
            PendingApproval(
                call_id=wait.call_id,
                activity_id=outcome.activity_id,
                value=wait.value,
                answer_schema=wait.answer_schema,
            )
            for wait, outcome in zip(ordered, resolved, strict=True)
        )
    if any(suspended):
        raise UnsupportedDurableOpenAIAgentsError(
            "OE returned a partly answered approval frontier"
        )
    # Every answer is checked before any is acknowledged, so a malformed one
    # fails the turn without a partly acknowledged frontier.
    answers = [unwrap_activity_outcome(outcome) for outcome in resolved]
    decisions = [
        _decision(wait.call_id, outcome.activity_id, answer)
        if wait.review_id is None
        else _review_decision(wait, outcome.activity_id, answer)
        for wait, outcome, answer in zip(ordered, resolved, answers, strict=True)
    ]
    for wait, outcome, answer, decision in zip(
        ordered, resolved, answers, decisions, strict=True
    ):
        await _acknowledge(outcome, answer, decision, wait)
    return tuple(decisions)


@contextmanager
def _run_path(boundaries: Sequence[ChildOperationBoundary]) -> Generator[None]:
    """Make the run that made a call the current operation path."""
    with ExitStack() as path:
        for boundary in boundaries:
            path.enter_context(child_operation_boundary_scope(boundary))
        yield


def _wait_command(
    attempt: AttemptContext,
    wait: _Wait,
    boundaries: Sequence[ChildOperationBoundary],
) -> ActivityCommand:
    # The wait is an activity of the run that made the call, so a nested
    # agent's approval sits beside that agent's model and tool activities.
    with _run_path(boundaries):
        return build_activity_command(
            attempt=attempt,
            kind=ACTIVITY_KIND_TOOL,
            name=wait.activity_name,
            activity_ordinal=allocate_activity_ordinal(wait.ordinal_key),
            semantic_input=wait.semantic_input,
        )


async def _acknowledge(
    outcome: ActivityOutcome,
    answer: object,
    decision: ApprovalDecision,
    wait: _Wait,
) -> None:
    wrapper = get_current_wrapper()
    if wrapper is None or wrapper.durable_memory is None:
        return
    activity = ActivityContext(
        workflow_identity=outcome.workflow_identity,
        activity_id=outcome.activity_id,
        attempt_id=outcome.attempt_id,
        fencing_token=outcome.fencing_token,
    )
    if wait.review_id is not None:
        # A reviewer's decision is not conversation content; the step still
        # cannot commit until Memory has acknowledged the wait's activity.
        await asyncio.to_thread(
            wrapper.durable_memory.acknowledge, wrapper.workflow, activity
        )
        return
    # A wait never runs through the secure tool wrapper, so nothing else
    # acknowledges its resolved activity to durable Memory, and a step with an
    # unacknowledged activity cannot commit. An approval is not conversation
    # content: the tool then runs and writes its own result. A rejection is:
    # the model reads its text as the call's output, so Memory gets the same
    # tool message and the call is not left open.
    rejection = None if decision.approved else rejection_text(decision)
    tool_call = cast(dict[str, JsonValue], wait.value["tool_call"])
    await asyncio.to_thread(
        wrapper.durable_memory.synchronize_tool,
        wrapper.workflow,
        activity,
        answer if rejection is None else rejection,
        user_id=get_current_user_id(),
        tool_call_id=None if rejection is None else decision.call_id,
        tool_name=wait.activity_name if rejection is None else str(tool_call["name"]),
    )


def rejection_text(decision: ApprovalDecision) -> str:
    """The text the SDK gives the model as a rejected call's output."""
    if decision.rejection_message is None:
        return DEFAULT_APPROVAL_REJECTION_MESSAGE
    return decision.rejection_message


def approval_call_id(item: ToolApprovalItem) -> str:
    """The model's call id for the call an interruption asks about."""
    return cast(str, getattr(item.raw_item, "call_id", ""))


def _position_key(position: ActivityPosition) -> bytes:
    return position.SerializeToString(deterministic=True)


def suspend_event(pending: Sequence[PendingApproval], *, resumed: bool) -> StreamEvent:
    """The caller-facing suspend event for a frontier of pending approvals."""
    interrupts: list[JsonValue] = [
        {"id": wait.activity_id, "value": wait.value} for wait in pending
    ]
    ids = [wait.activity_id for wait in pending]
    return StreamEvent(
        event="suspend",
        data={
            # Retained for callers that read one payload; interrupts has all.
            "suspend_payload": pending[0].value,
            "resumed": resumed,
            "messages": [],
            "metadata": {},
            "interrupts": interrupts,
            "resume_schema": {
                "type": "object",
                "required": ["resume_map"],
                "properties": {
                    "resume_map": {
                        "type": "object",
                        "required": cast(list[JsonValue], ids),
                        "properties": {
                            wait.activity_id: wait.answer_schema for wait in pending
                        },
                        "additionalProperties": False,
                    }
                },
                "additionalProperties": True,
            },
            "response": "",
        },
    )
