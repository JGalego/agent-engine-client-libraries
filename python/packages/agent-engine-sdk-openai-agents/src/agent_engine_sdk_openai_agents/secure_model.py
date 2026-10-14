"""AER model that sends every native Runner model call through OE."""

from __future__ import annotations

from collections.abc import AsyncIterator
from typing import Any, cast

from agent_engine_sdk import LLMResponse, LLMStreamChunk, LLMToolSchema, Message
from agents import FunctionTool, Model, ModelSettings, ModelTracing, Tool
from agents.agent_output import AgentOutputSchemaBase
from agents.handoffs import Handoff
from agents.items import ModelResponse, TResponseInputItem, TResponseStreamEvent
from agents.tracing import get_current_trace
from agents.tracing.traces import NoOpTrace
from agents.usage import Usage
from openai.types.responses import (
    Response,
    ResponseCompletedEvent,
    ResponseFunctionToolCall,
    ResponseOutputItem,
    ResponseOutputMessage,
    ResponseOutputText,
    ResponseTextDeltaEvent,
)
from openai.types.responses.response_prompt_param import ResponsePromptParam

from agent_engine_runner_shared.async_utils import iterate_in_thread
from agent_engine_runner_shared.context import get_current_wrapper
from agent_engine_runner_shared.secure_llm_proxy import (
    GuardrailReviewPending,
    SecureLLMProxy,
)
from agent_engine_sdk_openai_agents.agent_tools import (
    emit_nested_token,
    model_call_turn,
    nested_model_request,
    note_calls,
    preallocate_nested_runs,
)
from agent_engine_sdk_openai_agents.errors import UnsupportedDurableOpenAIAgentsError
from agent_engine_sdk_openai_agents.items import to_platform_messages, validate_items
from agent_engine_sdk_openai_agents.review import (
    REVIEW_TOOL_NAME,
    hold_for_review,
    is_review_tool,
    take_answered_review,
    without_review_calls,
)
from agent_engine_sdk_openai_agents.tools import json_text
from agent_engine_sdk_openai_agents.wire import invocation_options, tool_choice

__all__ = ["SecureModel"]


class SecureModel(Model):
    """Answer the Runner's model calls with OE LLM activities.

    A replacement attempt replays each recorded activity at the same
    operational step, so the Runner sees the same output items, with the same
    ids, that the original attempt committed.
    """

    def __init__(self, *, llm_id: str, model_name: str) -> None:
        self.llm_id = llm_id
        self.model_name = model_name

    async def get_response(
        self,
        system_instructions: str | None,
        input: str | list[TResponseInputItem],
        model_settings: ModelSettings,
        tools: list[Tool],
        output_schema: AgentOutputSchemaBase | None,
        handoffs: list[Handoff[Any, Any]],
        tracing: ModelTracing,
        *,
        previous_response_id: str | None,
        conversation_id: str | None,
        prompt: ResponsePromptParam | None,
    ) -> ModelResponse:
        async for event in self.stream_response(
            system_instructions,
            input,
            model_settings,
            tools,
            output_schema,
            handoffs,
            tracing,
            previous_response_id=previous_response_id,
            conversation_id=conversation_id,
            prompt=prompt,
        ):
            if isinstance(event, ResponseCompletedEvent):
                response = event.response
                return ModelResponse(
                    output=response.output,
                    usage=_usage(response),
                    response_id=response.id,
                )
        raise UnsupportedDurableOpenAIAgentsError("model call ended without a response")

    async def stream_response(
        self,
        system_instructions: str | None,
        input: str | list[TResponseInputItem],
        model_settings: ModelSettings,
        tools: list[Tool],
        output_schema: AgentOutputSchemaBase | None,
        handoffs: list[Handoff[Any, Any]],
        tracing: ModelTracing,
        *,
        previous_response_id: str | None,
        conversation_id: str | None,
        prompt: ResponsePromptParam | None,
    ) -> AsyncIterator[TResponseStreamEvent]:
        if previous_response_id or conversation_id or prompt is not None:
            raise UnsupportedDurableOpenAIAgentsError(
                "provider-hosted continuation would bypass OE-owned history"
            )
        # A run the app starts itself, as a guardrail's judge is, carries the
        # SDK's default run config, which asks for tracing. Inside the
        # adapter's run it inherits that run's disabled trace, so asking is
        # not refused: only a trace that is really being recorded is.
        if not tracing.is_disabled() and not isinstance(
            get_current_trace() or NoOpTrace(), NoOpTrace
        ):
            raise UnsupportedDurableOpenAIAgentsError(
                "OpenAI native tracing must be disabled in durable runs"
            )
        if model_settings.retry is not None:
            raise UnsupportedDurableOpenAIAgentsError(
                "ModelSettings.retry would make the Runner issue extra OE model "
                "activities; the Tool Pod already retries transient provider failures"
            )
        options = invocation_options(model_settings, output_schema)
        messages = _request_messages(system_instructions, input)
        wrapper = get_current_wrapper()
        if wrapper is None:
            raise UnsupportedDurableOpenAIAgentsError(
                "OpenAI Agents model calls require an AER execution context"
            )
        turn = model_call_turn()
        # After a review is answered the Runner asks again: that request is
        # the held call, sent again for OE to answer from the decision.
        answered = (
            None
            if turn is None or isinstance(input, str)
            else take_answered_review(input, turn)
        )
        step = wrapper.next_operational_step()
        proxy = SecureLLMProxy(
            oe_url=wrapper.oe_url,
            execution_id=wrapper.execution_id,
            llm_id=self.llm_id,
            model_name=self.model_name,
            # The model asks for a handoff by calling its tool; the Runner then
            # switches agents, so handoffs cross the activity as tools.
            bound_tools=[
                # The review tool stops the Runner; no model is offered it.
                *(_tool_schema(tool) for tool in tools if not is_review_tool(tool)),
                *(_handoff_schema(handoff) for handoff in handoffs),
            ]
            or None,
            bound_tool_choice=tool_choice(model_settings),
            operational_steps=wrapper.operational_steps,
            durable_memory=wrapper.durable_memory,
            # A response held for review comes back as a recorded halt, which
            # this model turns into the Runner's own wait.
            guardrail_review_protocol=True,
            sdk_handles_guardrail_review_pause=True,
        )
        chunks: list[LLMStreamChunk] = []
        stream = proxy.stream(
            messages=messages, step=step, options=options, pending_review=answered
        )
        held: GuardrailReviewPending | None = None
        with nested_model_request():
            try:
                async for chunk in iterate_in_thread(iter(stream)):
                    chunks.append(chunk)
                    if chunk.content:
                        # A nested agent's run is not streamed by the SDK; its
                        # text reaches the caller on the platform's subagent
                        # stream.
                        emit_nested_token(chunk.content)
                        yield ResponseTextDeltaEvent(
                            type="response.output_text.delta",
                            item_id=_message_id(wrapper.execution_id, step),
                            output_index=0,
                            content_index=0,
                            delta=chunk.content,
                            logprobs=[],
                            sequence_number=len(chunks),
                        )
            except GuardrailReviewPending as pending:
                held = pending
        if held is not None:
            if turn is None:
                raise UnsupportedDurableOpenAIAgentsError(
                    "a guardrail review halted a model call outside a turn"
                )
            if not any(is_review_tool(tool) for tool in tools):
                # Only an agent of the app's graph has the tool the Runner
                # stops on; a guardrail's judge is run outside that graph.
                raise UnsupportedDurableOpenAIAgentsError(
                    "a guardrail policy holds this model response for review, "
                    "which is supported only for an agent of the app's graph, "
                    "not for an agent a guardrail runs"
                )
            wrapper.observe_operational_step(held.step)
            call = hold_for_review(held, turn)
            note_calls([call])
            yield ResponseCompletedEvent(
                type="response.completed",
                response=_runner_response(
                    LLMResponse(content="", tool_calls=[call]),
                    execution_id=wrapper.execution_id,
                    step=step,
                ),
                sequence_number=1,
            )
            return
        if proxy.last_latest_step_number is not None:
            wrapper.observe_operational_step(proxy.last_latest_step_number)
        response = proxy.response_from_stream_chunks(chunks)
        if any(call.name == REVIEW_TOOL_NAME for call in response.tool_calls or []):
            # Only a halt OE recorded may stop the Runner for a review.
            raise UnsupportedDurableOpenAIAgentsError(
                f"the model called {REVIEW_TOOL_NAME!r}, which the platform reserves"
            )
        preallocate_nested_runs(tools, response.tool_calls or [])
        note_calls(response.tool_calls or [])
        yield ResponseCompletedEvent(
            type="response.completed",
            response=_runner_response(
                response, execution_id=wrapper.execution_id, step=step
            ),
            sequence_number=len(chunks) + 1,
        )


def _request_messages(
    system_instructions: str | None, input: str | list[TResponseInputItem]
) -> list[Message]:
    items: list[object] = (
        [{"role": "user", "content": input}] if isinstance(input, str) else list(input)
    )
    messages = to_platform_messages(validate_items(without_review_calls(items)))
    if system_instructions:
        messages.insert(0, Message(role="system", content=system_instructions))
    return messages


def _tool_schema(tool: Tool) -> LLMToolSchema:
    if not isinstance(tool, FunctionTool):
        raise UnsupportedDurableOpenAIAgentsError(
            f"{type(tool).__name__} would run outside platform tool execution"
        )
    return LLMToolSchema(
        name=tool.name,
        description=tool.description,
        parameters=cast(Any, tool.params_json_schema),
        type="function",
        strict=tool.strict_json_schema,
    )


def _handoff_schema(handoff: Handoff[Any, Any]) -> LLMToolSchema:
    return LLMToolSchema(
        name=handoff.tool_name,
        description=handoff.tool_description,
        parameters=cast(Any, handoff.input_json_schema),
        type="function",
        strict=handoff.strict_json_schema,
    )


def _message_id(execution_id: str, step: int) -> str:
    # The operational step is the call's replay-stable identity; the execution
    # id keeps ids distinct across turns of one session.
    return f"msg_{execution_id}_{step}"


def _runner_response(
    response: LLMResponse, *, execution_id: str, step: int
) -> Response:
    calls = response.tool_calls or []
    output: list[ResponseOutputItem] = []
    if response.content or not calls:
        output.append(
            ResponseOutputMessage(
                id=_message_id(execution_id, step),
                type="message",
                role="assistant",
                status="completed",
                content=[
                    ResponseOutputText(
                        type="output_text", text=response.content, annotations=[]
                    )
                ],
            )
        )
    for call in calls:
        if not call.id or not call.name:
            raise UnsupportedDurableOpenAIAgentsError(
                "model function calls require a call id and a name"
            )
        output.append(
            ResponseFunctionToolCall(
                # The SDK finds a stopped nested run again by its parent
                # call, item id included, in a table the whole process shares;
                # the execution id keeps another execution's call from
                # matching it.
                id=f"fc_{execution_id}_{call.id}",
                call_id=call.id,
                type="function_call",
                name=call.name,
                # Replayed arguments come back from OE reordered; canonical
                # text keeps the next model call's input identical on replay.
                arguments=json_text(call.args if call.args is not None else {}),
                status="completed",
            )
        )
    usage = response.usage
    return Response.model_validate(
        {
            "id": response.id or f"resp_{execution_id}_{step}",
            "created_at": 0,
            "model": "platform",
            "object": "response",
            "output": output,
            "tool_choice": "auto",
            "tools": [],
            "parallel_tool_calls": len(calls) > 1,
            "status": "completed",
            "usage": None
            if usage is None
            else {
                "input_tokens": usage.input_tokens or usage.prompt_tokens or 0,
                "output_tokens": usage.output_tokens or usage.completion_tokens or 0,
                "total_tokens": usage.total_tokens or 0,
                "input_tokens_details": {"cached_tokens": 0, "cache_write_tokens": 0},
                "output_tokens_details": {
                    "reasoning_tokens": usage.reasoning_tokens or 0
                },
            },
        }
    )


def _usage(response: Response) -> Usage:
    if response.usage is None:
        return Usage(requests=1)
    return Usage(
        requests=1,
        input_tokens=response.usage.input_tokens,
        output_tokens=response.usage.output_tokens,
        output_tokens_details=response.usage.output_tokens_details,
        total_tokens=response.usage.total_tokens,
    )
