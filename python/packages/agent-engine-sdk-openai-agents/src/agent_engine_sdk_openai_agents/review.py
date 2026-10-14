"""Hold a model response while a person reviews it for a guardrail policy.

A project's guardrail policy can require review of a model response. OE then
withholds the response and records the halt as the model call's result. The
Runner has one native way to stop until a caller answers: a call to a tool
that needs approval. So the secure model answers a halted call with a call to
the tool defined here, which no model is offered. The Runner stops for its
approval beside any other approval of the turn, the adapter records that wait
with OE as the review's, and once it is answered the Runner asks the model
again. That request is the held call sent again, naming the review: OE answers
it from the reviewer's decision, with the response or with a denial.
"""

from __future__ import annotations

from collections.abc import Iterable
from typing import Any, cast

from agent_engine_sdk import LLMToolCall
from agents import Agent, FunctionTool
from agents.tool_context import ToolContext

from agent_engine_runner_shared.secure_llm_proxy import GuardrailReviewPending
from agent_engine_sdk_openai_agents.errors import UnsupportedDurableOpenAIAgentsError
from agent_engine_sdk_openai_agents.tools import TurnContext

__all__ = [
    "REVIEW_TOOL_NAME",
    "hold_for_review",
    "install_review_tool",
    "is_review_tool",
    "take_answered_review",
    "without_review_calls",
]

REVIEW_TOOL_NAME = "agent_engine_guardrail_review"


class _ReviewHold:
    """The review tool's body: nothing, once its wait has been answered.

    The Runner runs it after the wait is answered and then asks the model
    again, which is where OE applies the reviewer's decision.
    """

    async def __call__(self, context: ToolContext[Any], arguments: str) -> str:
        del context, arguments
        return ""


def is_review_tool(tool: object) -> bool:
    return isinstance(tool, FunctionTool) and isinstance(
        tool.on_invoke_tool, _ReviewHold
    )


def install_review_tool(agent: Agent[Any]) -> None:
    """Give ``agent`` the tool its Runner stops on; doing it again is a no-op."""
    if any(is_review_tool(tool) for tool in agent.tools):
        return
    agent.tools = [
        *agent.tools,
        FunctionTool(
            name=REVIEW_TOOL_NAME,
            description="Holds a model response for guardrail review.",
            params_json_schema={
                "type": "object",
                "properties": {"review_id": {"type": "string"}},
                "required": ["review_id"],
                "additionalProperties": False,
            },
            on_invoke_tool=_ReviewHold(),
            strict_json_schema=True,
            needs_approval=True,
        ),
    ]


def hold_for_review(pending: GuardrailReviewPending, turn: TurnContext) -> LLMToolCall:
    """The call the Runner sees in place of a response OE withheld."""
    review_id = pending.review.review_id
    call_id = f"guardrail-review:{review_id}"
    if call_id in turn.reviews:
        raise UnsupportedDurableOpenAIAgentsError(
            f"guardrail review {review_id!r} halted two model calls in one turn"
        )
    turn.reviews[call_id] = pending
    return LLMToolCall(id=call_id, name=REVIEW_TOOL_NAME, args={"review_id": review_id})


def _review_call_id(item: object) -> str | None:
    """The call id of a call to the review tool, else nothing."""
    if not isinstance(item, dict):
        return None
    raw = cast(dict[str, object], item)
    call_id = raw.get("call_id")
    if not isinstance(call_id, str):
        return None
    if raw.get("type") == "function_call" and raw.get("name") == REVIEW_TOOL_NAME:
        return call_id
    return None


def take_answered_review(
    items: Iterable[object], turn: TurnContext
) -> GuardrailReviewPending | None:
    """The halted call this model request continues, taken so it resolves once.

    The Runner asks the model again after the review tool has run, with the
    tool's call and output at the end of the run's input.
    """
    listed = list(items)
    answered = {
        cast(dict[str, object], item).get("call_id")
        for item in listed
        if isinstance(item, dict)
        and cast(dict[str, object], item).get("type") == "function_call_output"
    }
    for item in listed:
        call_id = _review_call_id(item)
        if call_id is not None and call_id in answered and call_id in turn.reviews:
            return turn.reviews.pop(call_id)
    return None


def without_review_calls(items: Iterable[object]) -> list[object]:
    """``items`` without the review tool's calls and outputs.

    They stop the Runner and are not conversation: neither a later model call
    nor the committed history carries them.
    """
    listed = list(items)
    review_calls = {_review_call_id(item) for item in listed} - {None}
    return [
        item
        for item in listed
        if not (
            isinstance(item, dict)
            and cast(dict[str, object], item).get("call_id") in review_calls
        )
    ]
