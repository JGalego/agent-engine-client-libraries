"""Cancelled conversation history: the wire payload and the attempt-local capture.

The payload helpers project a bounded, best-effort transcript for OE's
``/executor/cancelled-history`` endpoint. ``CancelledHistory`` is the
framework-neutral, attempt-local capture each runtime drives through
``cancelled_history_scope``: it retains the last acknowledged root snapshot and
the turn's opener, and projects them onto the wire payload on cancellation.
"""

from __future__ import annotations

import hashlib
import json
from collections.abc import Sequence
from contextvars import ContextVar
from dataclasses import dataclass

from agent_engine_runner_shared.generated.workflow.v1.runtime_pb2 import AttemptContext
from agent_engine_runner_shared.generated.workflow.v1.state_pb2 import (
    MESSAGE_ROLE_ASSISTANT,
    MESSAGE_ROLE_USER,
    StateSnapshot,
    WorkflowMessage,
)
from agent_engine_runner_shared.workflow.protojson import (
    encode_protojson,
    json_to_proto_value,
)

__all__ = [
    "CancelledHistory",
    "bound_cancelled_history_messages",
    "cancelled_history_payload",
    "current_cancelled_history",
]

# OE decode bounds for the cancelled-history endpoint. Keep in step with
# internal/domains/orchestration-engine/internal/workflow/cancelled_history.go.
MESSAGE_LIMIT = 1000
MESSAGE_BYTES = 256 << 10
TOTAL_BYTES = 1 << 20
# Leave room inside the total body for the JSON envelope, identity, and escaping.
ENVELOPE_HEADROOM = 64 << 10
TRUNCATION_MARKER = "earlier completed steps omitted to fit cancelled-history limits"
TRUNCATED_OPENER_MARKER = "prompt omitted to fit cancelled-history limits"


def _wire_json(message: WorkflowMessage) -> str:
    return json.dumps(
        json.loads(encode_protojson(message)),
        ensure_ascii=False,
        allow_nan=False,
        separators=(",", ":"),
    )


def _go_escape_overhead(serialized: str) -> int:
    """Bytes Go's ``encoding/json`` adds when it HTML-escapes a string.

    OE measures each row twice: the raw UTF-8 ProtoJSON it receives, and its own
    ``json.Marshal`` of the decoded message, which escapes ``<``, ``>``, ``&`` and
    U+2028/U+2029 to six-byte ``\\uXXXX`` sequences. Budget the larger of the two
    so neither check rejects the row.
    """
    return 5 * (serialized.count("<") + serialized.count(">") + serialized.count("&")) + 3 * (
        serialized.count(" ") + serialized.count(" ")
    )


def _encoded_message_size(message: WorkflowMessage) -> int:
    serialized = _wire_json(message)
    return len(serialized.encode("utf-8")) + _go_escape_overhead(serialized)


def _fit_opener(opener: WorkflowMessage) -> WorkflowMessage:
    """Keep the opener's identity while shrinking content that cannot fit.

    OE requires the opener row to be present and within the per-message bound; a
    prompt larger than that (a pasted document) would otherwise reject the whole
    transcript, so replace its content with an explicit display marker.
    """
    if _encoded_message_size(opener) <= MESSAGE_BYTES:
        return opener
    return WorkflowMessage(
        id=opener.id,
        role=opener.role,
        content=json_to_proto_value(TRUNCATED_OPENER_MARKER),
    )


def bound_cancelled_history_messages(
    messages: Sequence[WorkflowMessage], opener_message_id: str
) -> list[WorkflowMessage]:
    """Project a display transcript that fits OE's cancelled-history decode bounds.

    OE rejects more than ``MESSAGE_LIMIT`` messages, any message over
    ``MESSAGE_BYTES`` once encoded, or a body over ``TOTAL_BYTES`` — in both the
    raw UTF-8 ProtoJSON and its HTML-escaped Go representation. A normally
    finalizable root can exceed those, so keep the opener plus the newest messages
    that fit, shrink an oversized opener, and record an explicit truncation marker
    when anything is dropped. Only shapes the display projection; never mutates
    acknowledged state.
    """
    budget = TOTAL_BYTES - ENVELOPE_HEADROOM
    original_opener = next((m for m in messages if m.id == opener_message_id), None)
    opener = _fit_opener(original_opener) if original_opener is not None else None

    def _fits(candidate: Sequence[WorkflowMessage]) -> bool:
        if len(candidate) > MESSAGE_LIMIT:
            return False
        total = 0
        for message in candidate:
            size = _encoded_message_size(message)
            if size > MESSAGE_BYTES:
                return False
            total += size
            if total > budget:
                return False
        return True

    if opener is original_opener and _fits(messages):
        return list(messages)
    if len(messages) == 1 and opener is not None:
        # A single-opener input has no completed work: keep exactly one bounded
        # row (the fitted opener carries its own content-omission marker), so the
        # report stays opener-only for OE's replacement-before-replay fallback.
        return [opener]

    marker = WorkflowMessage(
        role=MESSAGE_ROLE_ASSISTANT, content=json_to_proto_value(TRUNCATION_MARKER)
    )
    # Reserve the opener and the truncation marker (count and bytes) up front.
    reserved = _encoded_message_size(marker)
    if opener is not None:
        reserved += _encoded_message_size(opener)
    tail_budget = max(budget - reserved, 0)
    max_tail = max(MESSAGE_LIMIT - (1 if opener is not None else 0) - 1, 0)
    kept: list[WorkflowMessage] = []
    used = 0
    for message in reversed(messages):
        if opener is not None and message.id == opener_message_id:
            continue
        size = _encoded_message_size(message)
        if size > MESSAGE_BYTES or len(kept) + 1 > max_tail or used + size > tail_budget:
            continue
        kept.append(message)
        used += size
    kept.reverse()
    if opener is not None:
        kept.insert(0, opener)
    kept.insert(1 if opener is not None else 0, marker)
    return kept


def cancelled_history_payload(
    attempt: AttemptContext,
    opener_message_id: str,
    messages: Sequence[WorkflowMessage],
    accepted_step_ordinal: int,
    source_state_hash: str | None = None,
    base_session_revision: int = 0,
    provisional: bool = False,
) -> str:
    """Use one JSON serialization profile for the hash and complete request body."""
    bounded = bound_cancelled_history_messages(messages, opener_message_id)
    wire_messages = [json.loads(encode_protojson(message)) for message in bounded]
    serialized_messages = json.dumps(
        wire_messages, ensure_ascii=False, allow_nan=False, separators=(",", ":")
    )
    payload = {
        "workflow_identity": json.loads(encode_protojson(attempt.workflow_identity)),
        "attempt_id": attempt.attempt_id,
        "fencing_token": str(attempt.fencing_token),
        "opener_message_id": opener_message_id,
        "accepted_step_ordinal": str(accepted_step_ordinal),
        "base_session_revision": str(base_session_revision),
        "payload_hash": hashlib.sha256(serialized_messages.encode("utf-8")).hexdigest(),
    }
    if source_state_hash is not None:
        payload["source_state_hash"] = source_state_hash
    if provisional:
        # Reported at HITL suspension; becomes a stopped turn only on cancellation.
        payload["provisional"] = True
    payload["messages"] = wire_messages
    return json.dumps(payload, ensure_ascii=False, allow_nan=False, separators=(",", ":"))


@dataclass(frozen=True)
class _AcknowledgedState:
    snapshot: bytes
    ordinal: int
    source_hash: str | None


class CancelledHistory:
    """Keep protobuf bytes, never references to mutable graph or caller state."""

    def __init__(self, attempt: AttemptContext, opener: WorkflowMessage) -> None:
        self.attempt_key = (
            attempt.workflow_identity.execution_id,
            attempt.attempt_id,
            attempt.fencing_token,
        )
        self.opener_id = opener.id
        # Immutable copy of the selected USER opener, retained independently of
        # the acknowledged snapshots so a completed step that drops the opener
        # cannot leave the report without a matching USER message.
        self._opener_bytes = opener.SerializeToString()
        self._acknowledged: _AcknowledgedState | None = None

    def matches(self, attempt: AttemptContext) -> bool:
        return self.attempt_key == (
            attempt.workflow_identity.execution_id,
            attempt.attempt_id,
            attempt.fencing_token,
        )

    def normalized_input(self, state: StateSnapshot) -> None:
        if not state.messages:
            return
        users = [message for message in state.messages if message.role == MESSAGE_ROLE_USER]
        # The current turn's opener is the newest USER: a hook that carries the
        # conversation history appends the current prompt last, so preferring a
        # historical content match (e.g. a repeated prompt the hook rewrote) would
        # mark an earlier turn. System-only input keeps the request fallback opener
        # rather than adopting a non-USER message.
        selected = users[-1] if users else None
        if selected is not None:
            self.opener_id = selected.id
            self._opener_bytes = selected.SerializeToString()

    def acknowledge(
        self, state: StateSnapshot, ordinal: int, source_hash: str | None = None
    ) -> None:
        previous = self._acknowledged
        if previous is None or ordinal > previous.ordinal:
            self._acknowledged = _AcknowledgedState(state.SerializeToString(), ordinal, source_hash)

    def capture(self) -> tuple[str, list[WorkflowMessage], int, str | None]:
        acknowledged = self._acknowledged
        opener = WorkflowMessage.FromString(self._opener_bytes)
        if acknowledged is None:
            # No step was acknowledged, so the turn is only its opener. Projecting
            # to the detached USER opener (not the hook input) keeps the report
            # opener-only, which OE's replacement-before-replay fallback requires.
            messages = [opener]
        else:
            messages = list(StateSnapshot.FromString(acknowledged.snapshot).messages)
            # A completed step may drop the opener from its snapshot; restore the
            # detached copy in the display list without mutating the retained
            # snapshot or its ACK hash.
            if not any(message.id == self.opener_id for message in messages):
                messages.insert(0, opener)
        return (
            self.opener_id,
            messages,
            acknowledged.ordinal if acknowledged else 0,
            acknowledged.source_hash if acknowledged else None,
        )

    def clear(self) -> None:
        self._opener_bytes = b""
        self._acknowledged = None


current_cancelled_history: ContextVar[CancelledHistory | None] = ContextVar(
    "cancelled_history", default=None
)
