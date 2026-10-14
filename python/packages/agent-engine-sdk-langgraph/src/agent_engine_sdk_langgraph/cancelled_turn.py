"""Commit a terminal checkpoint for a cancelled turn so history can serve it.

Nothing checkpoints on cancel: LangGraph commits only at super-step
boundaries, and the drain tears the loop down mid-super-step, so the turn
vanishes from playground history. This writes one synthetic root-namespace
checkpoint from the last committed state (never the in-flight super-step —
its channel values are mid-mutation), stamps the turn-opening user message
with ``cancelled_turn`` so the stopped status lives exactly as long as the
message itself, and terminal-izes ``versions_seen`` for every graph node so
the tip presents as completed: the cancelled super-step must never look
resumable, and fork validation rejects snapshots with pending tasks.
"""

from __future__ import annotations

import copy
import logging
from collections.abc import Collection
from typing import Any
from uuid import uuid4

from langchain_core.messages import HumanMessage
from langgraph.checkpoint.base import empty_checkpoint

_logger = logging.getLogger(__name__)

CANCELLED_TURN_METADATA_KEY = "agent_engine_cancelled_turn"
CANCELLED_TURN_MESSAGE_ID_METADATA_KEY = "agent_engine_cancelled_turn_message_id"

# Framework-owned channel carrying queued Send packets (the deep agents'
# dispatched tool calls). Rebuilt from channel_values on read; absence reads
# as an empty queue.
TASKS_CHANNEL_KEY = "__pregel_tasks"

# Wire kwarg on the persisted turn-opening message. The history readers pass
# message kwargs through untouched, so the stopped status needs no marker
# scan and survives exactly as long as the message does.
CANCELLED_TURN_MESSAGE_KWARG = "cancelled_turn"


async def commit_cancelled_turn(
    saver: Any,
    *,
    thread_id: str,
    user_message: str,
    turn_message_id: str | None,
    node_names: Collection[str],
    resume: bool = False,
) -> str | None:
    """Write the cancelled-turn marker checkpoint; return the turn's user-message id.

    ``saver`` is the native Mongo-backed saver (unwrap ``PlatformCheckpointer``
    at the call site). ``turn_message_id`` is the id captured when the turn's
    graph input was built — identity, not prompt text, so repeated or
    hook-rewritten prompts can't misidentify the turn. ``None`` means no
    opener id was captured at build: a preparation-phase cancel, a resume
    turn, or a hook return outside the supported message-likes (those are
    converted and captured at build). A ``resume`` turn recovers its already
    persisted opener from the loaded checkpoint; otherwise the stopped message
    is appended under a freshly minted id — never matched against an older
    prompt's content. No ``checkpoint_writes`` rows are attached — the next
    invoke's cancelled-predecessor fence stays a no-op on this tip, and the
    half-dead super-step's orphaned writes are never joined to it.
    """
    config = {"configurable": {"thread_id": thread_id, "checkpoint_ns": ""}}
    latest = await saver.aget_tuple(config)

    channel_values: dict[str, Any] = {}
    channel_versions: dict[str, Any] = {}
    versions_seen: dict[str, dict[str, Any]] = {}
    parent_id: str | None = None
    prior_step = -1
    if latest is not None:
        channel_values = dict(latest.checkpoint.get("channel_values") or {})
        channel_versions = dict(latest.checkpoint.get("channel_versions") or {})
        versions_seen = {
            node: dict(seen)
            for node, seen in (latest.checkpoint.get("versions_seen") or {}).items()
        }
        parent_id = latest.checkpoint["id"]
        prior_step = (latest.metadata or {}).get("step", -1)
        clear_queued_tasks(channel_values)

    messages = list(channel_values.get("messages") or [])
    resolved_id = _locate_turn_message(messages, turn_message_id)
    if resolved_id is None and resume:
        # A resume opens no new user message: the opener is the last human
        # message already committed to the thread. Recover it here, from the
        # checkpoint the commit already loaded, so a Stop that beats the
        # preamble's capture still stamps the persisted opener instead of
        # appending a duplicate. Gated on resume — a fresh-turn preparation
        # cancel must append, never adopt an older identical prompt.
        resolved_id = _last_human_message_id(messages)
    if resolved_id is None and user_message:
        # The cancel beat even the input checkpoint: inject the user message
        # under its captured id or the turn cannot exist in history at all.
        resolved_id = turn_message_id or f"msg-cancelled-{uuid4()}"
        messages.append(
            _with_cancelled_stamp(HumanMessage(content=user_message, id=resolved_id))
        )
        channel_versions["messages"] = saver.get_next_version(
            channel_versions.get("messages"), None
        )
    elif resolved_id is not None:
        messages = [
            (
                _with_cancelled_stamp(message)
                if getattr(message, "id", None) == resolved_id
                else message
            )
            for message in messages
        ]
    channel_values["messages"] = messages

    # Every graph node reads as caught up — including nodes that never ran and
    # therefore have no versions_seen entry. A node absent there presents as
    # pending, and fork validation rejects a tip with pending work.
    terminalize_versions_seen(versions_seen, channel_versions, node_names)

    checkpoint = empty_checkpoint()
    checkpoint["channel_values"] = channel_values
    checkpoint["channel_versions"] = channel_versions
    checkpoint["versions_seen"] = versions_seen

    metadata: dict[str, Any] = {
        "source": "loop",
        "step": prior_step + 1,
        "parents": {},
        CANCELLED_TURN_METADATA_KEY: True,
    }
    if resolved_id is not None:
        metadata[CANCELLED_TURN_MESSAGE_ID_METADATA_KEY] = resolved_id
    await saver.aput(
        {
            "configurable": {
                "thread_id": thread_id,
                "checkpoint_ns": "",
                "checkpoint_id": parent_id,
            }
        },
        checkpoint,
        metadata,
        channel_versions,
    )
    return resolved_id


def clear_queued_tasks(channel_values: dict[str, Any]) -> dict[str, Any]:
    """Drop the framework's queued Send packets from copied channel values.

    The last committed checkpoint can carry Sends dispatched to a still-running
    tool (the deep agents route tool calls through ``__pregel_tasks``), and
    PUSH work is not consumed by terminalizing ``versions_seen`` — a marker
    built on it would present pending tasks and be rejected by fork
    validation, and a resume would run the cancelled work. Dropping the
    channel reads back as an empty queue.
    """
    channel_values.pop(TASKS_CHANNEL_KEY, None)
    return channel_values


def terminalize_versions_seen(
    versions_seen: dict[str, dict[str, Any]],
    channel_versions: dict[str, Any],
    node_names: Collection[str],
) -> dict[str, dict[str, Any]]:
    """Mark every node as having seen every current channel version.

    A node absent from (or behind in) ``versions_seen`` presents as pending:
    resume would schedule it and fork validation rejects a tip with pending
    work. Shared by the cancelled-turn commit and the fork copy of a
    marker-tipped thread, which replays values without task writes.
    """
    for node in set(node_names) | versions_seen.keys():
        node_seen = versions_seen.setdefault(node, {})
        for channel, version in channel_versions.items():
            node_seen[channel] = version
    return versions_seen


def _locate_turn_message(
    messages: list[Any], turn_message_id: str | None
) -> str | None:
    """Resolve the turn-opening message by the id captured at input build.

    No content fallback: an identical earlier prompt would make content
    matching stamp the previous turn's opener, so a missing captured id is
    always answered by appending, never by matching.
    """
    if turn_message_id is None:
        return None
    for message in messages:
        if getattr(message, "id", None) == turn_message_id:
            return turn_message_id
    return None


def _last_human_message_id(messages: list[Any]) -> str | None:
    """Id of the last human message, or None when it has none.

    Used only to recover a native HITL resume's already-persisted opener (see
    ``commit_cancelled_turn``); a fresh turn never reaches it, so it can never
    adopt an older identical prompt.
    """
    for message in reversed(messages):
        if getattr(message, "type", None) != "human":
            continue
        message_id = getattr(message, "id", None)
        return message_id if isinstance(message_id, str) and message_id else None
    return None


def _with_cancelled_stamp(message: Any) -> Any:
    """Copy of ``message`` carrying the cancelled-turn kwarg.

    Never mutates the deserialized original — checkpoint messages are shared
    state across reads.
    """
    kwargs = {
        **(getattr(message, "additional_kwargs", None) or {}),
        CANCELLED_TURN_MESSAGE_KWARG: True,
    }
    if hasattr(message, "model_copy"):
        return message.model_copy(update={"additional_kwargs": kwargs})
    clone = copy.copy(message)
    clone.additional_kwargs = kwargs
    return clone
