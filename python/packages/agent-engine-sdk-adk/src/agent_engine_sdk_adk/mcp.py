"""ADK tools for remote MCP servers configured in ``agent.yaml``.

The AER discovers each configured server's tools at App construction (see
``App._register_mcp_tools_from_config``) and exposes them to ADK agents
through ``App.tools()``. Execution routes through the same secure wrapper as
``@app.tool`` functions: the call goes to the Orchestration Engine first, which
forwards it to the Tool Pod, and the Tool Pod resolves the original MCP
identity from ``agent.yaml`` and performs the ``tools/call`` request. This
module must never open an MCP session itself — doing so from the AER would
bypass the audited I/O path.
"""

from __future__ import annotations

import asyncio
import logging
from collections.abc import Callable
from typing import TYPE_CHECKING, Any

from google.adk.tools.base_tool import BaseTool
from google.genai import types

from agent_engine_runner_shared.mcp_tools import MCPToolBinding, MCPToolResult

if TYPE_CHECKING:
    from google.adk.tools.tool_context import ToolContext

__all__ = ["RemoteMCPTool", "mcp_tool_description"]

_logger = logging.getLogger(__name__)


def mcp_tool_description(binding: MCPToolBinding) -> str:
    """Return the model-facing description for a discovered MCP tool."""

    return (binding.description or f"Remote MCP tool {binding.tool_name}").strip()


class RemoteMCPTool(BaseTool):
    """ADK tool backed by a remote MCP server via the Tool Pod.

    A ``BaseTool`` (rather than a plain callable) because the model-facing
    declaration must carry the server's raw input JSON schema, which ADK's
    signature introspection cannot express for nested or unioned schemas.
    This mirrors ADK's own ``MCPTool`` declaration handling.

    ``invoke`` is the secure-wrapped callable built by ``App.tools()``; it
    blocks on the OE round trip, so it runs on a worker thread to keep the
    AER's event loop (and its health endpoint) responsive.
    """

    def __init__(
        self,
        *,
        binding: MCPToolBinding,
        invoke: Callable[..., Any],
    ) -> None:
        super().__init__(
            name=binding.sdk_tool_name,
            description=mcp_tool_description(binding),
        )
        self._binding = binding
        self._invoke = invoke

    def _get_declaration(self) -> types.FunctionDeclaration:
        """Expose the server's own name, description, and input schema.

        The schema always travels in ``parameters_json_schema``: the platform's
        secure LLM bridge (``tools_dict_to_schemas``) and the ADK request
        builder read only that field, so ADK's Gemini-Schema conversion for the
        JSON-schema feature flag would silently drop the tool's arguments. The
        genai API accepts the JSON-schema field directly.
        """

        return types.FunctionDeclaration(
            name=self.name,
            description=self.description,
            parameters_json_schema=self._binding.input_schema,
        )

    async def run_async(
        self, *, args: dict[str, Any], tool_context: ToolContext
    ) -> Any:
        """Route one MCP tool call through the secure wrapper.

        ``tool_call_id`` is reserved: the wrapper pops it so OE can join the
        execution-log record to the session message, and it is excluded from
        the arguments sent to the MCP server. A model-supplied argument with
        that name is dropped — the platform's call identity must win — and the
        drop is logged so the collision is not silent.
        """

        arguments = dict(args)
        dropped = arguments.pop("tool_call_id", None)
        if dropped is not None:
            _logger.warning(
                "MCP tool %s received a model-supplied tool_call_id argument; "
                "the name is reserved for the platform's call identity, so the "
                "value was dropped",
                self.name,
            )
        tool_call_id = getattr(tool_context, "function_call_id", None)
        result = await asyncio.to_thread(
            self._invoke, tool_call_id=tool_call_id, **arguments
        )
        if isinstance(result, MCPToolResult):
            # Direct execution (no OE round trip) returns the model itself;
            # ADK needs a JSON-safe payload for the FunctionResponse.
            return result.model_dump(mode="json")
        return result
