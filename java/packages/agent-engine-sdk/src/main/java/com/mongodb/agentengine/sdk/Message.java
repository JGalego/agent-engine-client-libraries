package com.mongodb.agentengine.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Framework-neutral message.
 *
 * @param role who authored the message
 * @param content text or multimodal content
 * @param toolCalls tool calls requested by an assistant message
 * @param toolCallId on a tool message, the id of the call this message answers
 * @param isError whether a tool message reports a failed call
 * @param name the tool name on tool messages
 * @param id a framework-assigned message id
 * @param additionalKwargs framework metadata that stays attached to the message
 * @param responseMetadata provider response metadata
 */
public record Message(
    Role role,
    MessageContent content,
    List<LLMToolCall> toolCalls,
    String toolCallId,
    Boolean isError,
    String name,
    String id,
    Map<String, JsonNode> additionalKwargs,
    Map<String, JsonNode> responseMetadata) {

  public Message {
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(content, "content");
  }

  /** Creates a text-only message. */
  public static Message of(Role role, String text) {
    return new Message(role, new MessageContent.Text(text), null, null, null, null, null, null, null);
  }

  /** Creates a user message. */
  public static Message user(String text) {
    return of(Role.USER, text);
  }

  /** Creates an assistant message. */
  public static Message assistant(String text) {
    return of(Role.ASSISTANT, text);
  }

  /** Creates a system message. */
  public static Message system(String text) {
    return of(Role.SYSTEM, text);
  }
}
