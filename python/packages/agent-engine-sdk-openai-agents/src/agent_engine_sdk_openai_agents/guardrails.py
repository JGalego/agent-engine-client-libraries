"""An agent's own input and output guardrails, run where a replay repeats them.

The SDK starts an agent's guardrails together, and by default starts its input
guardrails beside the first model call. A guardrail may ask a model for its
verdict, which is an OE activity at the next operational step: guardrails
racing each other or the agent's model would take steps in an order a
replacement attempt could not repeat, and a model call racing an input
guardrail would be made before the guardrail could stop it. So each agent's
guardrails become one guardrail the Runner waits for, which runs them one at a
time in the order the agent lists them.
"""

from __future__ import annotations

from collections.abc import Sequence
from typing import Any

from agents import Agent, GuardrailFunctionOutput, InputGuardrail, OutputGuardrail
from agents.exceptions import (
    InputGuardrailTripwireTriggered,
    OutputGuardrailTripwireTriggered,
)
from agents.items import TResponseInputItem
from agents.run_context import RunContextWrapper

from agent_engine_runner_shared.secure_wrapper import PolicyDeniedException
from agent_engine_sdk_openai_agents.errors import UnsupportedDurableOpenAIAgentsError

__all__ = ["guardrail_denial", "run_guardrails_in_order"]

_PASSED = GuardrailFunctionOutput(output_info=None, tripwire_triggered=False)


class _InputGuardrailsInOrder:
    def __init__(self, guardrails: Sequence[InputGuardrail[Any]]) -> None:
        self.guardrails = tuple(guardrails)

    async def __call__(
        self,
        context: RunContextWrapper[Any],
        agent: Agent[Any],
        input: str | list[TResponseInputItem],
    ) -> GuardrailFunctionOutput:
        for guardrail in self.guardrails:
            result = await guardrail.run(agent, input, context)
            if result.output.tripwire_triggered:
                # Raised here, as the Runner would, to keep the name of the
                # guardrail that tripped.
                raise InputGuardrailTripwireTriggered(result)
        return _PASSED


class _OutputGuardrailsInOrder:
    def __init__(self, guardrails: Sequence[OutputGuardrail[Any]]) -> None:
        self.guardrails = tuple(guardrails)

    async def __call__(
        self, context: RunContextWrapper[Any], agent: Agent[Any], agent_output: Any
    ) -> GuardrailFunctionOutput:
        for guardrail in self.guardrails:
            result = await guardrail.run(context, agent, agent_output)
            if result.output.tripwire_triggered:
                raise OutputGuardrailTripwireTriggered(result)
        return _PASSED


def _require_name(
    agent: Agent[Any], stage: str, guardrail: InputGuardrail[Any] | OutputGuardrail[Any]
) -> None:
    """Refuse a guardrail whose name a denial could not report.

    The SDK names an unnamed guardrail after its function, which a
    ``functools.partial`` or a callable object does not have. Finding that out
    only when the guardrail trips would fail while the denial is being built.
    """
    try:
        guardrail.get_name()
    except AttributeError:
        raise UnsupportedDurableOpenAIAgentsError(
            f"an {stage} guardrail of agent {agent.name!r} has no name; pass "
            f"name= to {type(guardrail).__name__}"
        ) from None


def run_guardrails_in_order(agent: Agent[Any]) -> None:
    """Make the Runner run ``agent``'s guardrails in order and wait for them.

    Doing it again changes nothing: guardrails already combined are taken
    apart first, so one added to the agent since then keeps its place.
    Raises ``UnsupportedDurableOpenAIAgentsError`` for a guardrail without a
    name.
    """
    inputs: list[InputGuardrail[Any]] = []
    for guardrail in agent.input_guardrails:
        run = guardrail.guardrail_function
        inputs.extend(
            run.guardrails if isinstance(run, _InputGuardrailsInOrder) else [guardrail]
        )
    for guardrail in inputs:
        _require_name(agent, "input", guardrail)
    if inputs:
        agent.input_guardrails = [
            InputGuardrail(
                _InputGuardrailsInOrder(inputs),
                name="input_guardrails_in_order",
                run_in_parallel=False,
            )
        ]
    outputs: list[OutputGuardrail[Any]] = []
    for guardrail in agent.output_guardrails:
        check = guardrail.guardrail_function
        outputs.extend(
            check.guardrails
            if isinstance(check, _OutputGuardrailsInOrder)
            else [guardrail]
        )
    for guardrail in outputs:
        _require_name(agent, "output", guardrail)
    if outputs:
        agent.output_guardrails = [
            OutputGuardrail(
                _OutputGuardrailsInOrder(outputs), name="output_guardrails_in_order"
            )
        ]


def guardrail_denial(
    error: InputGuardrailTripwireTriggered | OutputGuardrailTripwireTriggered,
) -> PolicyDeniedException:
    """The turn failure a tripped guardrail becomes: a policy denial.

    It names the guardrail and leaves out what the guardrail reported, which
    may quote the text it stopped.
    """
    stage = "input" if isinstance(error, InputGuardrailTripwireTriggered) else "output"
    name = error.guardrail_result.guardrail.get_name()
    return PolicyDeniedException(f"the agent's {stage} guardrail {name!r} tripped")
