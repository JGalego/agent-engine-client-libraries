from __future__ import annotations

import hashlib
import json
import time

import httpx
import pytest

from agent_engine_runner_shared.generated.workflow.v1.runtime_pb2 import AttemptContext
from agent_engine_runner_shared.generated.workflow.v1.state_pb2 import (
    MESSAGE_ROLE_USER,
    FinalizeStepCommand,
    WorkflowMessage,
)
from agent_engine_runner_shared.server.drain import (
    DrainReason,
    DrainRegistry,
    DrainRequest,
)
from agent_engine_runner_shared.workflow.cancelled_history import (
    MESSAGE_BYTES,
    MESSAGE_LIMIT,
    TOTAL_BYTES,
    cancelled_history_payload,
)
from agent_engine_runner_shared.workflow.client import (
    AsyncWorkflowClient,
    WorkflowClient,
    WorkflowClientError,
)
from agent_engine_runner_shared.workflow.protojson import (
    json_to_proto_value,
)


def test_cancelled_history_wire_profile_and_hash() -> None:
    body = cancelled_history_payload(
        AttemptContext(attempt_id="attempt", fencing_token=9007199254740993),
        "opener",
        [
            WorkflowMessage(
                id="opener",
                role=MESSAGE_ROLE_USER,
                content=json_to_proto_value("雪"),
            )
        ],
        0,
    )
    payload = json.loads(body)
    assert payload["fencing_token"] == "9007199254740993"
    assert payload["accepted_step_ordinal"] == "0"
    assert payload["base_session_revision"] == "0"
    assert "source_state_hash" not in payload
    raw_messages = body.partition(',"messages":')[2][:-1]
    assert payload["payload_hash"] == hashlib.sha256(raw_messages.encode("utf-8")).hexdigest()
    assert payload["messages"][0]["content"] == "雪"


def test_suspended_capture_payload_marks_provisional() -> None:
    body = cancelled_history_payload(
        AttemptContext(attempt_id="attempt", fencing_token=1),
        "opener",
        [
            WorkflowMessage(
                id="opener", role=MESSAGE_ROLE_USER, content=json_to_proto_value("hello")
            )
        ],
        0,
        provisional=True,
    )
    payload = json.loads(body)
    assert payload["provisional"] is True
    # The hash still covers only the messages array, so the flag does not change it.
    raw_messages = body.partition(',"messages":')[2][:-1]
    assert payload["payload_hash"] == hashlib.sha256(raw_messages.encode("utf-8")).hexdigest()


def test_hash_covers_exact_wire_numbers_and_escaping() -> None:
    value = {"text": "<>&\u2028\u2029", "numbers": [1.0, 1e-6, 1e-7, 1e20, 1e21, -0.0]}
    body = cancelled_history_payload(
        AttemptContext(attempt_id="attempt", fencing_token=1),
        "opener",
        [WorkflowMessage(id="opener", role=MESSAGE_ROLE_USER, content=json_to_proto_value(value))],
        2,
        "original-state",
    )
    raw_messages = body.partition(',"messages":')[2][:-1]
    payload = json.loads(body)
    assert payload["payload_hash"] == hashlib.sha256(raw_messages.encode("utf-8")).hexdigest()
    assert payload["source_state_hash"] == "original-state"
    assert payload["messages"][0]["content"] == value


def test_bounded_projection_fits_oe_limits_for_an_oversized_tool_result() -> None:
    # A 300 KiB tool result finalizes fine but exceeds OE's 256 KiB per-message
    # bound; the projection must still produce an acceptable report.
    opener = WorkflowMessage(
        id="opener", role=MESSAGE_ROLE_USER, content=json_to_proto_value("hello")
    )
    huge = WorkflowMessage(
        id="tool-1",
        role=MESSAGE_ROLE_USER,
        content=json_to_proto_value("x" * (300 << 10)),
    )
    body = cancelled_history_payload(
        AttemptContext(attempt_id="attempt", fencing_token=1),
        "opener",
        [opener, huge],
        3,
        "hash-3",
    )
    messages = json.loads(body)["messages"]
    # Oversized message dropped; opener + explicit truncation marker retained.
    assert len(messages) == 2
    assert messages[0]["id"] == "opener"
    assert messages[0]["content"] == "hello"
    assert messages[1]["role"] == "MESSAGE_ROLE_ASSISTANT"
    assert "omitted" in messages[1]["content"]
    assert len(body.encode("utf-8")) <= TOTAL_BYTES
    payload = json.loads(body)
    assert payload["accepted_step_ordinal"] == "3"
    assert payload["source_state_hash"] == "hash-3"


def test_bounded_projection_caps_message_count() -> None:
    opener = WorkflowMessage(
        id="opener", role=MESSAGE_ROLE_USER, content=json_to_proto_value("hello")
    )
    filler = [
        WorkflowMessage(id=f"m{index}", role=MESSAGE_ROLE_USER, content=json_to_proto_value("x"))
        for index in range(1500)
    ]
    body = cancelled_history_payload(
        AttemptContext(attempt_id="attempt", fencing_token=1),
        "opener",
        [opener, *filler],
        0,
    )
    messages = json.loads(body)["messages"]
    assert len(messages) <= MESSAGE_LIMIT
    assert messages[0]["id"] == "opener"
    assert messages[1]["role"] == "MESSAGE_ROLE_ASSISTANT"  # truncation marker
    assert messages[-1]["id"] == "m1499"  # newest retained
    assert len(body.encode("utf-8")) <= TOTAL_BYTES


def test_bounded_projection_leaves_a_small_transcript_unchanged() -> None:
    opener = WorkflowMessage(
        id="opener", role=MESSAGE_ROLE_USER, content=json_to_proto_value("hello")
    )
    reply = WorkflowMessage(id="a1", role=MESSAGE_ROLE_USER, content=json_to_proto_value("world"))
    body = cancelled_history_payload(
        AttemptContext(attempt_id="attempt", fencing_token=1),
        "opener",
        [opener, reply],
        0,
    )
    assert [message["id"] for message in json.loads(body)["messages"]] == ["opener", "a1"]


def test_bounded_projection_measures_utf8_bytes_for_multibyte_content() -> None:
    # 100k CJK characters occupy ~300 KB of UTF-8 but only 100k characters; the
    # projection must measure bytes so OE does not reject the row.
    opener = WorkflowMessage(
        id="opener", role=MESSAGE_ROLE_USER, content=json_to_proto_value("hello")
    )
    cjk = WorkflowMessage(
        id="tool-1", role=MESSAGE_ROLE_USER, content=json_to_proto_value("雪" * 100_000)
    )
    body = cancelled_history_payload(
        AttemptContext(attempt_id="attempt", fencing_token=1), "opener", [opener, cjk], 0
    )
    messages = json.loads(body)["messages"]
    assert len(messages) == 2  # oversized row dropped; opener + truncation marker
    assert messages[0]["id"] == "opener"
    assert messages[1]["role"] == "MESSAGE_ROLE_ASSISTANT"
    assert len(body.encode("utf-8")) <= TOTAL_BYTES
    for message in messages:
        encoded = json.dumps(message, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        assert len(encoded) <= MESSAGE_BYTES


def test_bounded_projection_accounts_for_go_html_escaping() -> None:
    # Go's json.Marshal escapes <, >, & to \uXXXX, so a row that fits the raw wire
    # can still exceed OE's domain bound and reject the whole transcript.
    opener = WorkflowMessage(
        id="opener", role=MESSAGE_ROLE_USER, content=json_to_proto_value("hello")
    )
    html = WorkflowMessage(
        id="tool-1",
        role=MESSAGE_ROLE_USER,
        content=json_to_proto_value("<p>x</p>" * 10_000),
    )
    body = cancelled_history_payload(
        AttemptContext(attempt_id="attempt", fencing_token=1), "opener", [opener, html], 0
    )
    messages = json.loads(body)["messages"]
    assert len(messages) == 2
    for message in messages:
        serialized = json.dumps(message, ensure_ascii=False, separators=(",", ":"))
        domain = len(serialized.encode("utf-8")) + 5 * (
            serialized.count("<") + serialized.count(">") + serialized.count("&")
        )
        assert domain <= MESSAGE_BYTES


def test_bounded_projection_shrinks_an_oversized_opener() -> None:
    # A prompt larger than the per-message bound must keep its identity and role
    # but be shrunk, so the whole transcript is not rejected.
    opener = WorkflowMessage(
        id="opener",
        role=MESSAGE_ROLE_USER,
        content=json_to_proto_value("x" * (300 << 10)),
    )
    body = cancelled_history_payload(
        AttemptContext(attempt_id="attempt", fencing_token=1), "opener", [opener], 0
    )
    messages = json.loads(body)["messages"]
    # Exactly one bounded USER row: the opener keeps its identity, no separate
    # completed-work marker, so the report stays opener-only.
    assert len(messages) == 1
    assert messages[0]["id"] == "opener"
    assert messages[0]["role"] == "MESSAGE_ROLE_USER"
    assert "omitted" in messages[0]["content"]
    assert len(body.encode("utf-8")) <= TOTAL_BYTES


def test_bounded_projection_budgets_a_large_opener_against_a_full_tail() -> None:
    # A large opener plus an over-budget tail must still fit the 1 MiB body.
    opener = WorkflowMessage(
        id="opener",
        role=MESSAGE_ROLE_USER,
        content=json_to_proto_value("x" * (200 << 10)),
    )
    tail = [
        WorkflowMessage(
            id=f"m{index}",
            role=MESSAGE_ROLE_USER,
            content=json_to_proto_value("y" * (60 << 10)),
        )
        for index in range(20)
    ]
    body = cancelled_history_payload(
        AttemptContext(attempt_id="attempt", fencing_token=1), "opener", [opener, *tail], 0
    )
    assert len(body.encode("utf-8")) <= TOTAL_BYTES
    assert json.loads(body)["messages"][0]["id"] == "opener"


@pytest.mark.anyio
@pytest.mark.parametrize("reason", list(DrainReason))
async def test_cancelled_history_only_explicit_cancel_has_deadline_budget(
    reason: DrainReason,
) -> None:
    registry = DrainRegistry()
    registry.apply(
        DrainRequest(
            request_id="request",
            execution_id="execution",
            reason=reason,
            deadline_at_ms=int(time.time() * 1000) + 500,
        )
    )
    budget = registry.cancellation_report_budget("execution")
    assert (0 < budget <= 0.5) if reason == DrainReason.EXECUTION_CANCELLED else budget == 0
    assert registry.cancellation_report_budget("other") == 0


@pytest.mark.anyio
@pytest.mark.parametrize("remaining_ms,expected", [(10000, 3.0), (-100, 0.0)])
async def test_cancelled_history_report_budget_is_bounded(
    remaining_ms: int, expected: float
) -> None:
    registry = DrainRegistry()
    registry.apply(
        DrainRequest(
            request_id="request",
            execution_id="execution",
            reason=DrainReason.EXECUTION_CANCELLED,
            deadline_at_ms=int(time.time() * 1000) + remaining_ms,
        )
    )
    assert registry.cancellation_report_budget("execution") == expected


@pytest.mark.anyio
@pytest.mark.parametrize("sync", [False, True])
async def test_cancelled_history_transport_has_no_completion_or_retry(sync: bool) -> None:
    recorded = []

    def respond(request: httpx.Request) -> httpx.Response:
        recorded.append(request)
        return httpx.Response(412, json={})

    payload = cancelled_history_payload(
        AttemptContext(attempt_id="attempt", fencing_token=1),
        "opener",
        [
            WorkflowMessage(
                id="opener",
                role=MESSAGE_ROLE_USER,
                content=json_to_proto_value(
                    {
                        "text": "<>&雪\u2028\u2029",
                        "numbers": [1.25, 1e-6, 1.234567e-7, 1e20, 1e21, -0.0],
                    }
                ),
            )
        ],
        0,
    )
    transport = httpx.MockTransport(respond)
    with pytest.raises(WorkflowClientError):
        if sync:
            with httpx.Client(transport=transport) as http:
                with WorkflowClient("http://localhost", client=http) as client:
                    client.report_cancelled_history(payload)
        else:
            async with httpx.AsyncClient(transport=transport) as http:
                async with AsyncWorkflowClient("http://localhost", client=http) as client:
                    await client.report_cancelled_history(payload)
    assert len(recorded) == 1
    assert recorded[0].url.path == "/executor/cancelled-history"
    assert recorded[0].content == payload.encode("utf-8")
    raw_messages = recorded[0].content.partition(b',"messages":')[2][:-1]
    assert (
        json.loads(recorded[0].content)["payload_hash"] == hashlib.sha256(raw_messages).hexdigest()
    )


@pytest.mark.anyio
@pytest.mark.parametrize("sync", [False, True])
async def test_finalize_receipt_retains_authoritative_hash(sync: bool) -> None:
    transport = httpx.MockTransport(
        lambda request: httpx.Response(200, json={"source_state_hash": "oe-original"})
    )
    if sync:
        with httpx.Client(transport=transport) as http:
            with WorkflowClient("http://localhost", client=http) as client:
                entries = client.finalize_step(FinalizeStepCommand())
    else:
        async with httpx.AsyncClient(transport=transport) as http:
            async with AsyncWorkflowClient("http://localhost", client=http) as client:
                entries = await client.finalize_step(FinalizeStepCommand())
    assert entries == []
    assert entries.source_state_hash == "oe-original"
