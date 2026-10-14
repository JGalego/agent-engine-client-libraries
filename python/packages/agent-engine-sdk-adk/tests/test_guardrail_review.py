"""Review waits must preserve native ADK model and transfer semantics."""

from __future__ import annotations

from collections.abc import AsyncGenerator
from contextlib import contextmanager
from types import SimpleNamespace
from typing import Any
from unittest.mock import AsyncMock, MagicMock

import pytest
from agent_engine_sdk import AgentInput, RequestContext
from google.adk.agents import LlmAgent
from google.adk.events import Event
from google.adk.models import BaseLlm
from google.adk.models.llm_request import LlmRequest
from google.adk.models.llm_response import LlmResponse
from google.adk.runners import Runner
from google.adk.sessions import InMemorySessionService
from google.adk.tools.base_tool import BaseTool
from google.adk.tools.function_tool import FunctionTool
from google.adk.tools.long_running_tool import LongRunningFunctionTool
from google.adk.workflow import START, JoinNode, RetryConfig, Workflow, node
from google.genai import types

from agent_engine_runner_shared.context import current_wrapper
from agent_engine_runner_shared.generated.workflow.v1.activity_pb2 import (
    ACTIVITY_OUTCOME_KIND_COMPLETED,
    ACTIVITY_OUTCOME_KIND_SUSPENDED,
    ActivityContext,
    ActivityOutcome,
    StepActivityEntry,
)
from agent_engine_runner_shared.generated.workflow.v1.common_pb2 import WorkflowIdentity
from agent_engine_runner_shared.generated.workflow.v1.runtime_pb2 import AttemptContext
from agent_engine_runner_shared.models import GuardrailReviewHalt, ToolExecuteResponse
from agent_engine_runner_shared.secure_llm_proxy import SecureLLMProxy
from agent_engine_runner_shared.secure_wrapper import (
    OperationalStepAllocator,
    PolicyDeniedException,
)
from agent_engine_runner_shared.workflow.activity import semantic_input_from_json
from agent_engine_runner_shared.workflow.client import ActivityDispatch, ActivityReplay
from agent_engine_runner_shared.workflow.context import attempt_context_scope
from agent_engine_runner_shared.workflow.protojson import proto_struct_to_json
from agent_engine_sdk_adk.agent import ADKBaseAgent
from agent_engine_sdk_adk.errors import UnsupportedDurableADKError
from agent_engine_sdk_adk.guardrail_review import (
    REVIEW_METADATA_KEY,
    ReviewWait,
    install_review_carriers,
    review_wait_from_event,
)
from agent_engine_sdk_adk.secure_llm import SecureLlm


class _NativeCarrier(BaseTool):
    def __init__(self) -> None:
        super().__init__(name="adk_framework", description="Internal review wait")
        self.is_long_running = True

    async def run_async(self, *, args: dict[str, Any], tool_context: Any) -> None:
        del args, tool_context


class _ScriptedLlm(BaseLlm):
    requests: list[LlmRequest]
    responses: list[types.Content]

    async def generate_content_async(
        self, llm_request: LlmRequest, stream: bool = False
    ) -> AsyncGenerator[LlmResponse, None]:
        del stream
        self.requests.append(llm_request.model_copy(deep=True))
        yield LlmResponse(content=self.responses.pop(0), partial=False)


def _call(name: str, call_id: str, **args: Any) -> types.Content:
    return types.Content(
        role="model",
        parts=[
            types.Part(
                function_call=types.FunctionCall(name=name, id=call_id, args=args)
            )
        ],
    )


@pytest.mark.asyncio
async def test_native_carrier_pauses_and_preserves_transferred_current_turn() -> None:
    requests: list[LlmRequest] = []
    specialist = LlmAgent(
        name="specialist",
        include_contents="none",
        model=_ScriptedLlm(
            model="test",
            requests=requests,
            responses=[types.Content(role="model", parts=[types.Part(text="done")])],
        ),
    )
    model = _ScriptedLlm(
        model="test",
        requests=[],
        responses=[
            _call("adk_framework", "review-1", review_id="review-1"),
            _call("transfer_to_agent", "transfer-1", agent_name="specialist"),
        ],
    )
    carrier = _NativeCarrier()

    async def prepare(*, callback_context: Any, llm_request: LlmRequest) -> None:
        del callback_context
        # ADK dispatch needs a tool; provider declarations must remain unchanged.
        llm_request.tools_dict[carrier.name] = carrier

    root = LlmAgent(
        name="coordinator",
        model=model,
        sub_agents=[specialist],
        before_model_callback=prepare,
    )
    service = InMemorySessionService()
    await service.create_session(app_name="app", user_id="user", session_id="session")
    runner = Runner(agent=root, app_name="app", session_service=service)
    first = [
        event
        async for event in runner.run_async(
            user_id="user",
            session_id="session",
            new_message=types.Content(
                role="user", parts=[types.Part(text="original request")]
            ),
        )
    ]
    assert len(model.requests) == 1
    assert specialist.canonical_model.requests == []
    assert any("review-1" in (event.long_running_tool_ids or []) for event in first)
    second = [
        event
        async for event in runner.run_async(
            user_id="user",
            session_id="session",
            new_message=types.Content(
                role="user",
                parts=[
                    types.Part(
                        function_response=types.FunctionResponse(
                            name="adk_framework",
                            id="review-1",
                            response={"decision": "approve"},
                        ),
                    )
                ],
            ),
        )
    ]
    assert any(event.author == "specialist" for event in second)
    assert len(specialist.canonical_model.requests) == 1
    rendered = [
        content.model_dump_json(exclude_none=True)
        for content in specialist.canonical_model.requests[0].contents
    ]
    assert any("original request" in content for content in rendered)
    assert any("transfer_to_agent" in content for content in rendered)
    assert all(
        "adk_framework" not in content and "review-1" not in content
        for content in rendered
    )
    assert all(
        declaration.name != "adk_framework"
        for request in model.requests
        for tool in request.config.tools or []
        for declaration in tool.function_declarations or []
    )


class _DurableHistory:
    """Persisted OE outcomes shared by replacement attempts; no mocked ADK flow."""

    def __init__(self) -> None:
        self.activities: dict[str, Any] = {}
        self.commands: dict[str, Any] = {}
        self.decisions: dict[str, str] = {}
        self.suspensions: list[Any] = []

    def start_activity(self, command: Any) -> ActivityDispatch | ActivityReplay:
        key = command.position.SerializeToString().hex()
        if key in self.activities:
            assert self.commands[key].semantic_input == command.semantic_input
            return ActivityReplay(outcome=self.activities[key])
        self.commands[key] = command
        return ActivityDispatch(
            context=ActivityContext(
                workflow_identity=command.workflow_identity,
                activity_id=key,
                attempt_id=command.attempt_id,
                fencing_token=command.fencing_token,
            )
        )

    def report_outcome(self, outcome: Any) -> None:
        self.activities[outcome.activity_id] = outcome

    async def __aenter__(self) -> _DurableHistory:
        return self

    async def __aexit__(self, *_args: Any) -> None:
        pass

    async def finalize_step(self, command: Any) -> list[StepActivityEntry]:
        entries = []
        for suspension in command.suspensions:
            self.suspensions.append(suspension)
            key = suspension.position.SerializeToString().hex()
            value = proto_struct_to_json(suspension.suspension.context)["value"]
            review_id = value.get("guardrail_review", {}).get("review_id", key)
            outcome = ActivityOutcome(
                workflow_identity=command.workflow_identity,
                activity_id=key,
                attempt_id=command.attempt_id,
                fencing_token=command.fencing_token,
                outcome_kind=ACTIVITY_OUTCOME_KIND_SUSPENDED,
            )
            if review_id in self.decisions:
                outcome.outcome_kind = ACTIVITY_OUTCOME_KIND_COMPLETED
                outcome.result.CopyFrom(
                    semantic_input_from_json(
                        {
                            "guardrail_review": {
                                "review_id": review_id,
                                "decision": self.decisions[review_id],
                            }
                        }
                        if "guardrail_review" in value
                        else {"result": self.decisions[review_id]}
                    )
                )
            entries.append(
                StepActivityEntry(position=suspension.position, outcome=outcome)
            )
        return entries


class _ReviewRun:
    def __init__(self, monkeypatch: pytest.MonkeyPatch) -> None:
        self.history = _DurableHistory()
        self.calls: list[tuple[str | None, Any, int]] = []
        self.memory = MagicMock()
        self.complete = AsyncMock()
        self.held: dict[str, Any] = {"content": "original held answer"}
        self.provider_results: list[dict[str, Any] | Exception | None] = []
        self.reviewed: dict[str, tuple[Any, int]] = {}
        self.wrapper: Any = None
        monkeypatch.setattr(
            "agent_engine_sdk_adk.workflow.get_current_oe_url", lambda: "http://oe"
        )
        monkeypatch.setattr(
            "agent_engine_sdk_adk.workflow.AsyncWorkflowClient",
            lambda _url: self.history,
        )
        monkeypatch.setattr(
            "agent_engine_sdk_adk.agent.complete_durable_execution", self.complete
        )

        def make_proxy(**kwargs: Any) -> SecureLLMProxy:
            proxy = SecureLLMProxy(**kwargs)
            proxy._workflow = self.history

            def dispatch(
                request: Any, step: int, review_id: str | None = None
            ) -> ToolExecuteResponse:
                self.calls.append((review_id, request.model_copy(deep=True), step))
                if review_id is None:
                    result = (
                        self.provider_results.pop(0) if self.provider_results else None
                    )
                    if isinstance(result, Exception):
                        raise result
                    if result is not None:
                        return ToolExecuteResponse(
                            proceed=True, status="success", result=result
                        )
                    review_id = f"review-{step}"
                    self.reviewed[review_id] = (request.model_copy(deep=True), step)
                    return ToolExecuteResponse(
                        proceed=False,
                        status="require_review",
                        latest_step_number=step,
                        guardrail_review={
                            "review_id": review_id,
                            "allowed_decisions": ["approve", "deny"],
                            "reason": "human review",
                            "guardrails": [{"id": "policy-1"}],
                        },
                    )
                original, original_step = self.reviewed[review_id]
                assert request.messages == original.messages
                assert step > original_step
                if self.history.decisions[review_id] == "deny":
                    raise PolicyDeniedException("review denied")
                return ToolExecuteResponse(
                    proceed=True, status="success", result=self.held
                )

            proxy._request_oe_execution = dispatch
            return proxy

        monkeypatch.setattr(
            "agent_engine_sdk_adk.secure_llm.SecureLLMProxy", make_proxy
        )

    @contextmanager
    def attempt(self, *, replay: bool = False):
        steps = OperationalStepAllocator()
        self.wrapper = SimpleNamespace(
            oe_url="http://oe",
            execution_id="execution-1",
            operational_steps=steps,
            next_operational_step=steps.next,
            observe_operational_step=steps.observe_at_least,
            durable_memory=self.memory,
            workflow=self.history,
        )
        token = current_wrapper.set(self.wrapper)
        try:
            with attempt_context_scope(
                AttemptContext(
                    attempt_id="replacement" if replay else "initial",
                    fencing_token=7,
                    replay_mode=replay,
                    workflow_identity=WorkflowIdentity(
                        session_id="session-1", execution_id="execution-1"
                    ),
                )
            ):
                yield
        finally:
            current_wrapper.reset(token)

    def agent(self, *, name: str = "agent", **kwargs: Any) -> LlmAgent:
        return LlmAgent(
            name=name,
            model=SecureLlm(
                model="test",
                get_wrapper=lambda: self.wrapper,
                llm_id="llm",
            ),
            **kwargs,
        )

    async def collect(
        self, agent: Any, *, replay: bool = False, events: list[Any] | None = None
    ) -> list[Any]:
        with self.attempt(replay=replay):
            application = ADKBaseAgent(adk_agent=agent, app_name="app")
            collected = events if events is not None else []
            async for event in application.stream(
                RequestContext(
                    session_id="session-1",
                    user_id="user-1",
                    resume=replay,
                    resume_data={
                        wait.position.SerializeToString().hex(): {"decision": "approve"}
                        for wait in self.history.suspensions
                    }
                    if replay
                    else None,
                ),
                AgentInput(payload={"message": "original request"}),
            ):
                collected.append(event)
            return collected


@pytest.mark.asyncio
@pytest.mark.parametrize("fallback_tool", [False, True])
async def test_denied_review_cannot_recover_through_model_error_callback(
    monkeypatch: pytest.MonkeyPatch, fallback_tool: bool
) -> None:
    run = _ReviewRun(monkeypatch)
    recovered: list[Exception] = []
    effects: list[str] = []

    def perform_effect(value: str) -> dict[str, str]:
        effects.append(value)
        return {"result": value}

    async def recover(
        *, callback_context: Any, llm_request: LlmRequest, error: Exception
    ) -> LlmResponse:
        del callback_context, llm_request
        recovered.append(error)
        part = (
            types.Part.from_function_call(
                name="perform_effect", args={"value": "denied fallback effect"}
            )
            if fallback_tool
            else types.Part.from_text(text="denied fallback answer")
        )
        return LlmResponse(content=types.Content(role="model", parts=[part]))

    def agent() -> LlmAgent:
        return run.agent(tools=[perform_effect], on_model_error_callback=[recover])

    first = await run.collect(agent())
    assert [event.event for event in first] == ["suspend"]
    run.history.decisions["review-1"] = "deny"
    run.provider_results = [{"content": "fallback complete"}]
    events: list[Any] = []
    with pytest.raises(PolicyDeniedException, match="review denied"):
        await run.collect(agent(), replay=True, events=events)
    assert events == []
    assert recovered == []
    assert effects == []
    assert [call[0] for call in run.calls] == [None, "review-1"]
    run.complete.assert_not_awaited()
    run.memory.synchronize_llm.assert_not_called()
    run.memory.synchronize_tool.assert_not_called()


@pytest.mark.asyncio
@pytest.mark.parametrize("decision", ["approve", "deny"])
@pytest.mark.parametrize("fallback_tool", [False, True])
@pytest.mark.parametrize(
    "callback_form",
    [
        "sync_keyword",
        "async_keyword_list",
        "sync_positional",
        "async_positional_only_list",
    ],
)
async def test_review_resolution_cannot_be_replaced_by_before_model_callback(
    monkeypatch: pytest.MonkeyPatch,
    decision: str,
    fallback_tool: bool,
    callback_form: str,
) -> None:
    run = _ReviewRun(monkeypatch)
    callbacks: list[bool] = []
    effects: list[str] = []

    def perform_effect(value: str) -> dict[str, str]:
        effects.append(value)
        return {"result": value}

    def replace(
        *, callback_context: Any, llm_request: LlmRequest
    ) -> LlmResponse | None:
        del llm_request
        answered = any(
            response.name == "adk_framework"
            for event in callback_context.session.events
            for response in event.get_function_responses()
        )
        substituted = any(callbacks)
        callbacks.append(answered)
        if not answered or substituted:
            return None
        part = (
            types.Part.from_function_call(
                name="perform_effect", args={"value": "review fallback effect"}
            )
            if fallback_tool
            else types.Part.from_text(text="review fallback answer")
        )
        return LlmResponse(content=types.Content(role="model", parts=[part]))

    async def async_replace(**kwargs: Any) -> LlmResponse | None:
        return replace(**kwargs)

    def positional_replace(ctx: Any, request: LlmRequest) -> LlmResponse | None:
        return replace(callback_context=ctx, llm_request=request)

    async def positional_only_replace(
        ctx: Any, request: LlmRequest, /
    ) -> LlmResponse | None:
        return replace(callback_context=ctx, llm_request=request)

    def agent() -> LlmAgent:
        callbacks_by_form = {
            "sync_keyword": replace,
            "async_keyword_list": [async_replace],
            "sync_positional": positional_replace,
            "async_positional_only_list": [positional_only_replace],
        }
        return run.agent(
            tools=[perform_effect],
            before_model_callback=callbacks_by_form[callback_form],
        )

    first = await run.collect(agent())
    assert [event.event for event in first] == ["suspend"]
    run.history.decisions["review-1"] = decision
    events: list[Any] = []
    if decision == "deny":
        with pytest.raises(PolicyDeniedException, match="review denied"):
            await run.collect(agent(), replay=True, events=events)
        assert events == []
        run.complete.assert_not_awaited()
        run.memory.synchronize_llm.assert_not_called()
    else:
        await run.collect(agent(), replay=True, events=events)
        assert events[-1].event == "result"
        assert events[-1].data["response"] == "original held answer"
        assert all("review fallback" not in str(event.data) for event in events)
        run.complete.assert_awaited_once()
        run.memory.synchronize_llm.assert_called_once()
    assert callbacks == [False, False]
    assert effects == []
    assert [call[0] for call in run.calls] == [None, "review-1"]
    run.memory.synchronize_tool.assert_not_called()


@pytest.mark.asyncio
@pytest.mark.parametrize("callback_form", ["keyword", "positional", "positional_only"])
async def test_before_model_callback_still_replaces_an_ordinary_model_call(
    monkeypatch: pytest.MonkeyPatch, callback_form: str
) -> None:
    run = _ReviewRun(monkeypatch)
    callbacks: list[str] = []

    async def replace(*, callback_context: Any, llm_request: LlmRequest) -> LlmResponse:
        del callback_context, llm_request
        callbacks.append("ordinary call")
        return LlmResponse(
            content=types.Content(
                role="model", parts=[types.Part.from_text(text="ordinary fallback")]
            )
        )

    async def positional_replace(ctx: Any, request: LlmRequest) -> LlmResponse:
        return await replace(callback_context=ctx, llm_request=request)

    async def positional_only_replace(ctx: Any, request: LlmRequest, /) -> LlmResponse:
        return await replace(callback_context=ctx, llm_request=request)

    callbacks_by_form = {
        "keyword": replace,
        "positional": positional_replace,
        "positional_only": positional_only_replace,
    }
    events = await run.collect(
        run.agent(before_model_callback=[callbacks_by_form[callback_form]])
    )
    assert events[-1].data["response"] == "ordinary fallback"
    assert callbacks == ["ordinary call"]
    assert run.calls == []
    assert run.history.suspensions == []
    run.complete.assert_awaited_once()


@pytest.mark.asyncio
@pytest.mark.parametrize("decision", ["approve", "deny"])
@pytest.mark.parametrize("positional", [False, True])
async def test_before_agent_callback_cannot_replace_review_resolution(
    monkeypatch: pytest.MonkeyPatch, decision: str, positional: bool
) -> None:
    run = _ReviewRun(monkeypatch)
    callbacks: list[bool] = []

    def replace(*, callback_context: Any) -> types.Content | None:
        answered = any(
            response.name == "adk_framework"
            for event in callback_context.session.events
            for response in event.get_function_responses()
        )
        callbacks.append(answered)
        if answered:
            return types.Content(
                role="model", parts=[types.Part.from_text(text="cached fallback")]
            )
        return None

    async def positional_replace(ctx: Any, /) -> types.Content | None:
        return replace(callback_context=ctx)

    def agent() -> LlmAgent:
        return run.agent(
            before_agent_callback=[positional_replace] if positional else replace
        )

    first = await run.collect(agent())
    assert [event.event for event in first] == ["suspend"]
    run.history.decisions["review-1"] = decision
    events: list[Any] = []
    if decision == "deny":
        with pytest.raises(PolicyDeniedException, match="review denied"):
            await run.collect(agent(), replay=True, events=events)
        assert events == []
        run.complete.assert_not_awaited()
        run.memory.synchronize_llm.assert_not_called()
    else:
        await run.collect(agent(), replay=True, events=events)
        assert events[-1].data["response"] == "original held answer"
        assert all("cached fallback" not in str(event.data) for event in events)
        run.complete.assert_awaited_once()
    assert callbacks == [False, False]
    assert [call[0] for call in run.calls] == [None, "review-1"]


@pytest.mark.asyncio
@pytest.mark.parametrize("positional", [False, True])
async def test_before_agent_callback_still_replaces_an_ordinary_invocation(
    monkeypatch: pytest.MonkeyPatch, positional: bool
) -> None:
    run = _ReviewRun(monkeypatch)
    callbacks: list[str] = []

    def replace(*, callback_context: Any) -> types.Content:
        del callback_context
        callbacks.append("ordinary invocation")
        return types.Content(
            role="model", parts=[types.Part.from_text(text="cached ordinary answer")]
        )

    async def positional_replace(ctx: Any, /) -> types.Content:
        return replace(callback_context=ctx)

    events = await run.collect(
        run.agent(before_agent_callback=[positional_replace] if positional else replace)
    )
    assert events[-1].data["response"] == "cached ordinary answer"
    assert callbacks == ["ordinary invocation"]
    assert run.calls == []
    assert run.history.suspensions == []
    run.complete.assert_awaited_once()


@pytest.mark.asyncio
@pytest.mark.parametrize("target", ["node", "workflow", "sub_agent"])
@pytest.mark.parametrize("exceptions", [None, ["TimeoutError"]])
async def test_native_node_retries_are_rejected_before_dispatch(
    monkeypatch: pytest.MonkeyPatch, target: str, exceptions: list[str] | None
) -> None:
    run = _ReviewRun(monkeypatch)
    retry = RetryConfig(max_attempts=2, exceptions=exceptions)
    if target == "sub_agent":
        child = run.agent(name="child", retry_config=retry)
        root = run.agent(name="root", sub_agents=[child])
    else:
        reviewed = run.agent(retry_config=retry if target == "node" else None)
        root = Workflow(
            name="flow",
            edges=[(START, reviewed)],
            retry_config=retry if target == "workflow" else None,
        )

    with pytest.raises(UnsupportedDurableADKError, match="node retry_config"):
        await run.collect(root)
    assert run.calls == []
    assert run.history.activities == {}
    assert run.history.suspensions == []
    run.complete.assert_not_awaited()


@pytest.mark.asyncio
async def test_model_error_callback_still_recovers_provider_errors(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    run = _ReviewRun(monkeypatch)
    provider_error = RuntimeError("provider unavailable")
    run.provider_results = [provider_error]
    recovered: list[Exception] = []

    def recover(
        *, callback_context: Any, llm_request: LlmRequest, error: Exception
    ) -> LlmResponse:
        del callback_context, llm_request
        recovered.append(error)
        return LlmResponse(
            content=types.Content(
                role="model", parts=[types.Part.from_text(text="provider fallback")]
            )
        )

    events = await run.collect(run.agent(on_model_error_callback=recover))
    assert recovered == [provider_error]
    assert [event.data["content"] for event in events if event.event == "token"] == [
        "provider fallback"
    ]
    assert events[-1].event == "result"
    assert [
        (message["role"], message["content"]) for message in events[-1].data["messages"]
    ] == [("assistant", "provider fallback")]
    run.complete.assert_awaited_once()


@pytest.mark.asyncio
@pytest.mark.parametrize("decision", ["approve", "deny"])
async def test_durable_review_replays_on_replacement_and_resolves_original_call(
    monkeypatch: pytest.MonkeyPatch,
    decision: str,
) -> None:
    run = _ReviewRun(monkeypatch)
    first = await run.collect(run.agent())
    assert [event.event for event in first] == ["suspend"]
    assert first[0].data["messages"] == []
    assert first[0].data["response"] == ""
    assert len(run.calls) == 1
    assert (
        next(iter(run.history.activities.values())).outcome_kind
        == ACTIVITY_OUTCOME_KIND_COMPLETED
    )
    run.memory.synchronize_llm.assert_not_called()
    run.memory.synchronize_tool.assert_not_called()

    original_position = run.history.suspensions[0].position
    run.history.decisions["review-1"] = decision
    if decision == "deny":
        with pytest.raises(PolicyDeniedException, match="review denied"):
            await run.collect(run.agent(), replay=True)
        run.complete.assert_not_awaited()
    else:
        second = await run.collect(run.agent(), replay=True)
        assert [
            event.data["content"] for event in second if event.event == "token"
        ] == ["original held answer"]
        assert [
            (message["role"], message["content"])
            for message in second[-1].data["messages"]
        ] == [("assistant", "original held answer")]
        run.complete.assert_awaited_once()
        run.memory.synchronize_llm.assert_called_once()
    assert len(run.calls) == 2
    assert run.calls[1][0] == "review-1"
    assert run.history.suspensions[1].position == original_position
    run.memory.synchronize_tool.assert_not_called()


@pytest.mark.asyncio
@pytest.mark.parametrize("include_contents", ["default", "none"])
async def test_approved_transfer_keeps_user_request_without_new_user_turn(
    monkeypatch: pytest.MonkeyPatch,
    include_contents: str,
) -> None:
    run = _ReviewRun(monkeypatch)
    run.held = {
        "content": "",
        "tool_calls": [
            {
                "name": "transfer_to_agent",
                "id": "transfer-1",
                "args": {"agent_name": "specialist"},
            }
        ],
    }

    def agent() -> LlmAgent:
        specialist = LlmAgent(
            name="specialist",
            include_contents=include_contents,
            model=_ScriptedLlm(
                model="test",
                requests=[],
                responses=[
                    types.Content(
                        role="model", parts=[types.Part(text="specialist result")]
                    ),
                ],
            ),
        )
        return run.agent(sub_agents=[specialist])

    await run.collect(agent())
    assert len(run.calls) == 1
    run.history.decisions["review-1"] = "approve"
    replacement = agent()
    second = await run.collect(replacement, replay=True)
    assert len(run.calls) == 2
    specialist_model = replacement.sub_agents[0].canonical_model
    assert len(specialist_model.requests) == 1
    contents = [
        content.model_dump_json(exclude_none=True)
        for content in specialist_model.requests[0].contents
    ]
    assert any("original request" in content for content in contents)
    assert any("transfer_to_agent" in content for content in contents)
    assert all(
        "adk_framework" not in content and "review-1" not in content
        for content in contents
    )
    assert second[-1].data["response"] == "specialist result"


@pytest.mark.asyncio
async def test_review_after_another_agents_output_keeps_resolving_binding(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    run = _ReviewRun(monkeypatch)
    run.provider_results = [{"content": "intake facts"}, None]

    def workflow() -> Workflow:
        intake = run.agent(name="intake")
        reviewed = run.agent(name="reviewed")
        return Workflow(name="flow", edges=[(START, intake), (intake, reviewed)])

    await run.collect(workflow())
    assert len(run.calls) == 2
    run.history.decisions["review-2"] = "approve"
    second = await run.collect(workflow(), replay=True)
    assert len(run.calls) == 3
    assert run.calls[-1][0] == "review-2"
    assert second[-1].data["response"] == "original held answer"


@pytest.mark.asyncio
@pytest.mark.parametrize("decision", ["approve", "deny"])
async def test_held_tool_call_runs_only_after_approval(
    monkeypatch: pytest.MonkeyPatch,
    decision: str,
) -> None:
    run = _ReviewRun(monkeypatch)
    effects: list[str] = []

    def perform_effect(value: str) -> dict[str, str]:
        effects.append(value)
        return {"result": value}

    run.held = {
        "content": "",
        "tool_calls": [
            {
                "id": "effect-1",
                "name": "perform_effect",
                "args": {"value": "approved effect"},
            }
        ],
    }
    run.provider_results = [None, {"content": "effect complete"}]
    await run.collect(run.agent(tools=[perform_effect]))
    assert effects == []
    run.history.decisions["review-1"] = decision
    if decision == "deny":
        with pytest.raises(PolicyDeniedException):
            await run.collect(run.agent(tools=[perform_effect]), replay=True)
        assert effects == []
    else:
        second = await run.collect(run.agent(tools=[perform_effect]), replay=True)
        assert effects == ["approved effect"]
        assert second[-1].data["response"] == "effect complete"


@pytest.mark.asyncio
async def test_carrier_keeps_tool_free_structured_output_and_callbacks() -> None:
    seen: list[LlmRequest] = []

    async def callback(*, callback_context: Any, llm_request: LlmRequest) -> None:
        del callback_context
        seen.append(llm_request.model_copy(deep=True))

    async def run(with_carrier: bool) -> LlmRequest:
        agent = LlmAgent(
            name="agent",
            output_schema={
                "type": "object",
                "properties": {"answer": {"type": "string"}},
            },
            before_model_callback=callback,
            model=_ScriptedLlm(
                model="test",
                requests=[],
                responses=[
                    types.Content(
                        role="model", parts=[types.Part(text='{"answer":"ok"}')]
                    )
                ],
            ),
        )
        if with_carrier:
            install_review_carriers(agent)
            install_review_carriers(agent)
        assert agent.tools == []
        service = InMemorySessionService()
        await service.create_session(
            app_name="app", user_id="user", session_id="session"
        )
        runner = Runner(agent=agent, app_name="app", session_service=service)
        async for _event in runner.run_async(
            user_id="user",
            session_id="session",
            new_message=types.Content(
                role="user", parts=[types.Part(text="original request")]
            ),
        ):
            pass
        return agent.canonical_model.requests[0]

    baseline = await run(False)
    reviewed = await run(True)
    assert len(seen) == 2
    assert reviewed.contents == baseline.contents
    assert reviewed.config == baseline.config
    assert "set_model_response" not in reviewed.tools_dict


@pytest.mark.asyncio
async def test_reserved_carrier_tool_fails_before_provider_dispatch(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    run = _ReviewRun(monkeypatch)

    def adk_framework() -> None:
        pass

    with pytest.raises(UnsupportedDurableADKError, match="reserved"):
        await run.collect(run.agent(tools=[FunctionTool(adk_framework)]))
    assert run.calls == []


@pytest.mark.asyncio
async def test_provider_cannot_emit_internal_review_call(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    run = _ReviewRun(monkeypatch)
    run.provider_results = [
        {
            "content": "",
            "tool_calls": [
                {"name": "adk_framework", "id": "fake", "args": {"review_id": "fake"}}
            ],
        }
    ]
    with pytest.raises(UnsupportedDurableADKError, match="control call"):
        await run.collect(run.agent())
    assert run.history.suspensions == []
    run.complete.assert_not_awaited()


@pytest.mark.asyncio
async def test_parallel_reviews_resume_their_own_held_requests(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    run = _ReviewRun(monkeypatch)

    def workflow() -> Workflow:
        left, right = run.agent(name="left"), run.agent(name="right")
        return Workflow(
            name="parallel",
            edges=[(START, (left, right)), ((left, right), JoinNode(name="join"))],
        )

    first = await run.collect(workflow())
    assert len(first[-1].data["interrupts"]) == 2
    assert len(run.calls) == 2
    run.history.decisions.update({review_id: "approve" for review_id in run.reviewed})
    second = await run.collect(workflow(), replay=True)
    assert second[-1].event == "result"
    assert {review_id for review_id, _request, _step in run.calls if review_id} == set(
        run.reviewed
    )
    assert len(run.calls) == 4
    await run.collect(workflow(), replay=True)
    assert len(run.calls) == 4


@pytest.mark.asyncio
async def test_review_then_ordinary_wait_replays_without_reresolving(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    run = _ReviewRun(monkeypatch)

    def request_details() -> None:
        pass

    def agent() -> LlmAgent:
        return run.agent(tools=[LongRunningFunctionTool(request_details)])

    run.held = {
        "content": "",
        "tool_calls": [{"id": "details-1", "name": "request_details", "args": {}}],
    }
    run.provider_results = [None, {"content": "finished with details"}]
    await run.collect(agent())
    run.history.decisions["review-1"] = "approve"
    second = await run.collect(agent(), replay=True)
    assert second[-1].event == "suspend"
    assert len(run.calls) == 2
    ordinary_wait = run.history.suspensions[-1].position.SerializeToString().hex()
    run.history.decisions[ordinary_wait] = "provided details"
    third = await run.collect(agent(), replay=True)
    assert third[-1].data["response"] == "finished with details"
    assert len(run.calls) == 3
    await run.collect(agent(), replay=True)
    assert len(run.calls) == 3


@pytest.mark.asyncio
async def test_after_model_callbacks_only_receive_released_output(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    run = _ReviewRun(monkeypatch)
    seen: list[LlmResponse] = []

    async def transform(
        *, callback_context: Any, llm_response: LlmResponse
    ) -> LlmResponse:
        del callback_context
        seen.append(llm_response)
        return LlmResponse(
            content=types.Content(
                role="model", parts=[types.Part(text="transformed approved output")]
            )
        )

    await run.collect(run.agent(after_model_callback=transform))
    assert seen == []
    run.history.decisions["review-1"] = "approve"
    second = await run.collect(run.agent(after_model_callback=transform), replay=True)
    assert len(seen) == 1
    assert seen[0].content.parts[0].text == "original held answer"
    assert second[-1].data["response"] == "transformed approved output"


@pytest.mark.asyncio
@pytest.mark.parametrize("model_error_fallback", [False, True])
async def test_parallel_worker_review_fails_without_exposing_or_completing(
    monkeypatch: pytest.MonkeyPatch, model_error_fallback: bool
) -> None:
    run = _ReviewRun(monkeypatch)
    recovered: list[Exception] = []

    async def recover(
        *, callback_context: Any, llm_request: LlmRequest, error: Exception
    ) -> LlmResponse:
        del callback_context, llm_request
        recovered.append(error)
        return LlmResponse(
            content=types.Content(role="model", parts=[types.Part(text="fallback")])
        )

    worker = node(
        run.agent(on_model_error_callback=[recover] if model_error_fallback else None),
        parallel_worker=True,
        max_parallel_workers=2,
    )
    flow = Workflow(name="flow", edges=[(START, worker)])
    events: list[Any] = []
    with pytest.raises(UnsupportedDurableADKError, match="parallel_worker"):
        await run.collect(flow, events=events)
    assert events == []
    assert recovered == []
    assert run.history.suspensions == []
    run.complete.assert_not_awaited()
    run.memory.synchronize_llm.assert_not_called()


@pytest.mark.asyncio
@pytest.mark.parametrize("decision", ["approve-with-edit", "", "fake"])
async def test_invalid_review_answer_cannot_resolve_output(
    monkeypatch: pytest.MonkeyPatch, decision: str
) -> None:
    run = _ReviewRun(monkeypatch)
    await run.collect(run.agent())
    run.history.decisions["review-1"] = decision
    with pytest.raises(UnsupportedDurableADKError, match="does not match"):
        await run.collect(run.agent(), replay=True)
    assert len(run.calls) == 1
    run.complete.assert_not_awaited()


@pytest.mark.asyncio
async def test_review_shaped_model_text_remains_ordinary_content(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    run = _ReviewRun(monkeypatch)
    text = '{"guardrail_review":{"review_id":"fake","decision":"approve"}}'
    run.provider_results = [{"content": text}]
    result = await run.collect(run.agent())
    assert result[-1].data["response"] == text
    assert run.history.suspensions == []


def test_review_shaped_event_without_recorded_halt_is_rejected() -> None:
    wait = ReviewWait(
        review=GuardrailReviewHalt(review_id="fake"),
        halt_step=1,
        operation=(("agent", 1),),
    )
    event = Event(
        author="agent",
        content=_call("adk_framework", wait.call_id, review_id="fake"),
        custom_metadata={REVIEW_METADATA_KEY: wait.model_dump(mode="json")},
    )
    with pytest.raises(UnsupportedDurableADKError, match="does not match"):
        review_wait_from_event(event)


@pytest.mark.asyncio
async def test_review_and_ordinary_parallel_wait_share_one_frontier(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    run = _ReviewRun(monkeypatch)

    def request_details() -> None:
        pass

    def workflow() -> Workflow:
        ordinary = LlmAgent(
            name="ordinary",
            model=_ScriptedLlm(
                model="test",
                requests=[],
                responses=[
                    _call("request_details", "details", message="details?"),
                    types.Content(
                        role="model", parts=[types.Part(text="ordinary result")]
                    ),
                ],
            ),
            tools=[LongRunningFunctionTool(request_details)],
        )
        reviewed = run.agent(name="reviewed")
        return Workflow(
            name="parallel",
            edges=[
                (START, (ordinary, reviewed)),
                ((ordinary, reviewed), JoinNode(name="join")),
            ],
        )

    first = await run.collect(workflow())
    assert len(first[-1].data["interrupts"]) == 2
    run.history.decisions["review-1"] = "approve"
    for wait in run.history.suspensions:
        value = proto_struct_to_json(wait.suspension.context)["value"]
        if "guardrail_review" not in value:
            run.history.decisions[wait.position.SerializeToString().hex()] = (
                "provided details"
            )
    result = await run.collect(workflow(), replay=True)
    assert result[-1].event == "result"
    assert len(run.calls) == 2


@pytest.mark.asyncio
@pytest.mark.parametrize("hook", ["before_tool_callback", "after_tool_callback"])
@pytest.mark.parametrize("callback_form", ["keywords", "positional", "positional_only"])
@pytest.mark.parametrize("reviewed", [False, True])
async def test_application_tool_callbacks_preserve_real_tool_behavior(
    monkeypatch: pytest.MonkeyPatch, hook: str, callback_form: str, reviewed: bool
) -> None:
    run = _ReviewRun(monkeypatch)
    seen: list[str] = []

    async def answer_tool(**kwargs: Any) -> dict[str, str]:
        seen.append(kwargs["tool"].name)
        return {"result": "automatic tool answer"}

    def before(t: BaseTool, a: Any, ctx: Any) -> dict[str, str]:
        return {"result": record(t)}

    def after(t: BaseTool, a: Any, ctx: Any, response: Any) -> dict[str, str]:
        return {"result": record(t)}

    async def before_positional(t: BaseTool, a: Any, ctx: Any, /) -> dict[str, str]:
        return {"result": record(t)}

    async def after_positional(
        t: BaseTool, a: Any, ctx: Any, response: Any, /
    ) -> dict[str, str]:
        return {"result": record(t)}

    def record(tool: BaseTool) -> str:
        seen.append(tool.name)
        return "automatic tool answer"

    callback: Any = answer_tool
    if callback_form == "positional":
        callback = before if hook == "before_tool_callback" else after
    elif callback_form == "positional_only":
        callback = [
            before_positional if hook == "before_tool_callback" else after_positional
        ]

    effects: list[str] = []

    def lookup() -> dict[str, str]:
        effects.append("lookup")
        return {"result": "tool result"}

    def agent() -> LlmAgent:
        return run.agent(tools=[lookup], **{hook: callback})

    run.held = {
        "content": "",
        "tool_calls": [{"id": "lookup-1", "name": "lookup", "args": {}}],
    }
    run.provider_results = [
        None if reviewed else run.held,
        {"content": "tool follow-up"},
    ]
    first = await run.collect(agent())
    if reviewed:
        assert seen == []
        assert effects == []
        assert len(run.calls) == 1
        assert first[-1].event == "suspend"
        run.history.decisions["review-1"] = "approve"
        result = await run.collect(agent(), replay=True)
        assert len(run.calls) == 3
    else:
        result = first
        assert run.history.suspensions == []
        assert len(run.calls) == 2
    assert seen == ["lookup"]
    assert effects == ([] if hook == "before_tool_callback" else ["lookup"])
    assert result[-1].data["response"] == "tool follow-up"
    run.complete.assert_awaited_once()
