package com.mongodb.agentengine.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A single message in a session's conversation history.
 *
 * @param id stable identifier: the framework's id when available, otherwise one derived from the
 *     session id and position
 * @param role {@code "user"}, {@code "assistant"}, {@code "tool"} or {@code "system"}; unknown
 *     framework types pass through unchanged
 * @param content flat-string content; multimodal content is collapsed to its text parts
 * @param timestamp ISO 8601 persistence-boundary timestamp; empty when unavailable
 * @param sessionId the session this message belongs to
 * @param name the tool name on tool messages, empty otherwise
 * @param toolCalls tool calls requested by an assistant message; each {@code id} is echoed back by
 *     the matching tool result's {@code toolCallId}
 * @param toolCallId on a tool message, the id of the assistant tool call it answers
 * @param additionalKwargs framework message metadata that stays attached to the message, including
 *     UI artifact metadata
 */
public record SessionMessage(
    String id,
    String role,
    String content,
    String timestamp,
    String sessionId,
    String name,
    List<LLMToolCall> toolCalls,
    String toolCallId,
    Map<String, JsonNode> additionalKwargs) {

  public SessionMessage {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(content, "content");
    Objects.requireNonNull(timestamp, "timestamp");
    Objects.requireNonNull(sessionId, "sessionId");
    name = name == null ? "" : name;
  }
}
