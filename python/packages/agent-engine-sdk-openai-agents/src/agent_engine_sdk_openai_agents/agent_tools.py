"""Agents used as tools: each call runs a nested agent under its own OE path.

``Agent.as_tool`` gives the SDK a function tool whose body is a nested Runner
run. The durable runtime reads that tool before a turn does any work, runs
every nested run under an operation path named after the tool and keyed by the
parent call id, and forwards the nested agent's progress on the platform's
subagent stream.
"""

from __future__ import annotations

import asyncio
import inspect
import json
from collections.abc import Generator, Iterable
from contextlib import contextmanager
from contextvars import ContextVar
from dataclasses import dataclass
from typing import Any, cast

from agent_engine_sdk import LLMToolCall, StreamEvent
from agents import Agent, FunctionTool, Tool
from agents import agent as sdk_agent
from agents import agent_tool_state as sdk_agent_tool_state
from agents import tool as sdk_tool
from agents.tool_context import ToolContext

from agent_engine_runner_shared.workflow.context import (
    ChildOperationBoundary,
    child_operation_boundary_scope,
    preallocate_child_operation_ordinals,
)
from agent_engine_sdk_openai_agents.errors import UnsupportedDurableOpenAIAgentsError
from agent_engine_sdk_openai_agents.tools import (
    ModelCall,
    ToolCallFailed,
    TurnContext,
    current_turn,
    json_text,
)

__all__ = [
    "NestedAgentRun",
    "emit_nested_token",
    "forget_stopped_runs",
    "model_call_turn",
    "nested_model_request",
    "is_agent_tool",
    "note_calls",
    "preallocate_nested_runs",
    "scoped_agent_tool",
]

# Options ``as_tool`` accepts that the durable runtime does not own yet: user
# callbacks would run again on every replay, a run config or session could
# route model calls or history outside OE, and provider continuation ids would
# bypass OE-owned history.
_UNSUPPORTED_OPTIONS = (
    "on_stream",
    "hooks",
    "run_config",
    "session",
    "previous_response_id",
    "conversation_id",
)


def is_agent_tool(tool: object) -> bool:
    """Whether ``tool`` was built by ``Agent.as_tool``."""
    return isinstance(tool, FunctionTool) and bool(
        getattr(tool, "_is_agent_tool", False)
    )


def scoped_agent_tool(tool: FunctionTool) -> Agent[Any]:
    """Validate a native agent tool, scope its nested runs, and return its agent.

    Scoping replaces the tool's invoker with one that runs the SDK's own
    inside the nested operation path; doing it again is a no-op.
    """
    invoker = tool.on_invoke_tool
    sdk_invoker = (
        invoker.sdk_invoker if isinstance(invoker, NestedAgentRun) else invoker
    )
    captured = _captured_by_as_tool(sdk_invoker)
    target = getattr(tool, "_agent_instance", None)
    if not isinstance(target, Agent) or not set(_UNSUPPORTED_OPTIONS) <= set(captured):
        # A hand-built or wrapped tool, or an SDK whose as_tool() differs from
        # the ones this adapter recognizes: what the Runner would invoke
        # cannot be read.
        raise UnsupportedDurableOpenAIAgentsError(
            f"agent tool {tool.name!r} must be built with Agent.as_tool(...) "
            "from an openai-agents version this adapter supports"
        )
    unsupported = {name: captured[name] is not None for name in _UNSUPPORTED_OPTIONS}
    unsupported["a callable needs_approval"] = not isinstance(tool.needs_approval, bool)
    unsupported["a callable is_enabled"] = not isinstance(tool.is_enabled, bool)
    unsupported["guardrails"] = bool(
        tool.tool_input_guardrails or tool.tool_output_guardrails
    )
    # The SDK would cancel the nested run and tell the calling model it timed
    # out, where a nested run that does not finish must fail the turn.
    unsupported["a native timeout"] = tool.timeout_seconds is not None
    found = [name for name, present in unsupported.items() if present]
    if found:
        raise UnsupportedDurableOpenAIAgentsError(
            f"agent tool {tool.name!r} uses {', '.join(found)}, which durable "
            "OpenAI Agents runs do not support yet"
        )
    if not isinstance(invoker, NestedAgentRun):
        tool.on_invoke_tool = NestedAgentRun(sdk_invoker, tool.name)
        # By default the SDK turns a failed nested run into model-visible text.
        # A failure inside a durable turn must fail the turn instead.
        sdk_tool.set_function_tool_failure_error_function(tool, None)
    return cast(Agent[Any], target)


def _captured_by_as_tool(invoker: object) -> dict[str, Any]:
    """What ``Agent.as_tool`` captured in the invoker it built, else nothing.

    The Runner calls the tool's invoker itself, so every layer must be the
    SDK's own: its failure-handling invoker around exactly the implementation
    ``as_tool`` defines. Anything else could run code this validation never saw.
    """
    if type(invoker) is not getattr(
        sdk_tool, "_FailureHandlingFunctionToolInvoker", None
    ):
        return {}
    implementation = getattr(invoker, "_invoke_tool_impl", None)
    if not (
        inspect.isfunction(implementation)
        and implementation.__module__ == sdk_agent.__name__
        and implementation.__qualname__ == "Agent.as_tool.<locals>._run_agent_impl"
    ):
        return {}
    return dict(inspect.getclosurevars(implementation).nonlocals)


@dataclass(frozen=True)
class _NestedRun:
    """The nested agent run the current task is inside."""

    name: str
    tool_call_id: str
    turn: TurnContext | None
    # This run's child operations, outermost first.
    boundaries: tuple[ChildOperationBoundary, ...]

    def emit(self, event: str, **data: str) -> None:
        if self.turn is not None:
            self.turn.events.put_nowait(
                StreamEvent(
                    event=event,
                    data={
                        "source": self.name,
                        "tool_call_id": self.tool_call_id,
                        **data,
                    },
                )
            )


_NESTED_RUN: ContextVar[_NestedRun | None] = ContextVar(
    "agent_engine_openai_agents_nested_run", default=None
)


def emit_nested_token(content: str) -> None:
    """Forward model text to the caller's stream when a nested agent wrote it."""
    nested = _NESTED_RUN.get()
    if nested is not None:
        nested.emit("token", content=content)


def note_calls(calls: Iterable[LLMToolCall]) -> None:
    """Remember which run made each call a model returned this turn.

    An approval names its call by id alone, so within a turn an id belongs to
    one run: the turn's own agents, or one nested agent run.
    """
    nested = _NESTED_RUN.get()
    turn = model_call_turn()
    if turn is None:
        return
    boundaries = nested.boundaries if nested is not None else ()
    for call in calls:
        if not call.id:
            continue
        known = turn.calls.get(call.id)
        if known is None:
            turn.calls[call.id] = ModelCall(boundaries, len(turn.calls))
        elif known.boundaries != boundaries:
            raise UnsupportedDurableOpenAIAgentsError(
                f"tool call id {call.id!r} was used by two agent runs in one turn"
            )


def model_call_turn() -> TurnContext | None:
    """The turn a model call belongs to, whichever run makes it."""
    nested = _NESTED_RUN.get()
    return nested.turn if nested is not None else current_turn()


@contextmanager
def nested_model_request() -> Generator[None]:
    """Keep the turn open while a nested agent's model request is under way.

    A sibling's failure cancels the nested run, but the request's worker thread
    cannot be interrupted, and the SDK does not wait for it. Wrap only the part
    of the request that runs to its end or is cancelled; ``iterate_in_thread``
    waits for its worker before a cancellation leaves it.
    """
    nested = _NESTED_RUN.get()
    if nested is None or nested.turn is None:
        yield
        return
    finished: asyncio.Future[None] = asyncio.get_running_loop().create_future()
    nested.turn.track(finished)
    try:
        yield
    finally:
        finished.set_result(None)


class NestedAgentRun:
    """Run one agent-tool call as a nested operation of the turn."""

    def __init__(self, sdk_invoker: Any, tool_name: str) -> None:
        self.sdk_invoker = sdk_invoker
        self._tool_name = tool_name

    async def __call__(self, context: ToolContext[Any], arguments: str) -> str:
        call_id = context.tool_call_id
        if not call_id:
            raise UnsupportedDurableOpenAIAgentsError(
                f"agent tool {self._tool_name!r} was called without a call id"
            )
        # The parent call id tells apart repeated and concurrent calls to the
        # same agent tool; the secure model preallocated their order.
        boundary = ChildOperationBoundary(name=self._tool_name, occurrence_key=call_id)
        outer = _NESTED_RUN.get()
        nested = _NestedRun(
            name=self._tool_name,
            tool_call_id=call_id,
            turn=context.context if isinstance(context.context, TurnContext) else None,
            boundaries=(*(outer.boundaries if outer is not None else ()), boundary),
        )
        token = _NESTED_RUN.set(nested)
        try:
            with child_operation_boundary_scope(boundary):
                nested.emit(
                    "subagent_start",
                    subagent_name=self._tool_name,
                    description=_request(arguments),
                )
                summary = ""
                waiting = False
                try:
                    result = await self.sdk_invoker(context, arguments)
                    waiting = _waits_for_approval(context, nested.turn)
                    if waiting:
                        # Once the approval is answered the SDK calls this tool
                        # again with the same call id, and the run continues
                        # under the same path.
                        return result
                    # The SDK would store a non-string result as its Python
                    # repr; durable history needs text it can read back.
                    summary = json_text(result)
                    return summary
                finally:
                    if not waiting:
                        nested.emit(
                            "subagent_end",
                            subagent_name=self._tool_name,
                            summary=summary,
                        )
        except ToolCallFailed:
            raise
        except Exception as error:
            raise ToolCallFailed(error) from error
        finally:
            _NESTED_RUN.reset(token)


def _waits_for_approval(context: ToolContext[Any], turn: TurnContext | None) -> bool:
    """Whether the nested run this call started stopped for an approval.

    The SDK's invoker returns nothing in that case and keeps the stopped run
    for the parent run to report and later continue.
    """
    if context.tool_call is None:
        return False
    scope = sdk_agent_tool_state.get_agent_tool_state_scope(context)
    stopped = sdk_agent_tool_state.peek_agent_tool_run_result(
        context.tool_call, scope_id=scope
    )
    waiting = bool(getattr(stopped, "interruptions", None))
    if waiting and turn is not None:
        turn.stopped_calls.append(context.tool_call)
    return waiting


def forget_stopped_runs(turn: TurnContext) -> None:
    """Drop the stopped nested runs the SDK still holds for this turn.

    The SDK keeps a stopped nested run in a process-wide table, found again by
    its parent call's id, name, and arguments. A turn that suspends never
    continues it, and the attempt that resumes the turn replays the same parent
    call, often in this process: left in the table, the stale run would be
    continued in place of the replay, without its model call reaching OE.

    Applying an answer also makes the SDK copy a stopped run, under scopes and
    call objects of its own. It releases a copy when its call object is
    collected, which the copy itself prevents, so a long-lived runner would
    keep one for every answered approval inside a nested agent.

    A run and its copies are found by what the parent call says; the item id
    in it names this execution, so no other execution's run matches. Two
    attempts of one execution would match each other: this relies on OE
    starting an attempt only after the previous one suspended or its lease
    expired.
    """
    held = sdk_agent_tool_state._agent_tool_run_results_by_signature  # pyright: ignore[reportPrivateUsage]
    for tool_call in turn.stopped_calls:
        said = sdk_agent_tool_state._tool_call_signature(tool_call)  # pyright: ignore[reportPrivateUsage]
        for scope, held_said in list(held):
            if held_said == said:
                sdk_agent_tool_state.drop_agent_tool_run_result(
                    tool_call, scope_id=scope
                )
    turn.stopped_calls.clear()


def _request(arguments: str) -> str:
    """The request text of a default ``as_tool`` call, else its raw arguments."""
    try:
        parsed = json.loads(arguments)
    except json.JSONDecodeError:
        return arguments
    request = (
        cast(dict[str, object], parsed).get("input")
        if isinstance(parsed, dict)
        else None
    )
    return request if isinstance(request, str) else arguments


def preallocate_nested_runs(
    tools: Iterable[Tool], calls: Iterable[LLMToolCall]
) -> None:
    """Fix the order of a response's agent-tool calls before any of them runs.

    Each call becomes a nested operation whose occurrence ordinal depends on
    allocation order, so calls that run concurrently are allocated here in the
    model's call order.
    """
    agent_tools = {tool.name for tool in tools if is_agent_tool(tool)}
    runs = [
        ChildOperationBoundary(name=call.name, occurrence_key=call.id)
        for call in calls
        if call.name in agent_tools and call.id
    ]
    if runs:
        preallocate_child_operation_ordinals(runs)
