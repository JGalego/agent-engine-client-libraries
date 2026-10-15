package com.mongodb.agentengine.runner.models;

import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.agentengine.sdk.LLMTokenUsage;
import com.mongodb.agentengine.sdk.LLMToolCall;
import com.mongodb.agentengine.sdk.ToolCallChunk;
import java.util.List;
import java.util.Map;

/**
 * One server-sent event of a streamed LLM call. Content events carry a delta; the last event has
 * {@code done} (with usage and timing), {@code interrupted}, or {@code error}.
 *
 * @param toolCallChunks partial tool-call fragments
 * @param toolCalls complete tool calls, from older senders
 * @param retryable with {@code error}, whether the same stream URL may be retried
 */
public record LlmPodStreamEvent(
    String content,
    List<ToolCallChunk> toolCallChunks,
    List<LLMToolCall> toolCalls,
    String id,
    String name,
    Map<String, JsonNode> additionalKwargs,
    Map<String, JsonNode> responseMetadata,
    Boolean done,
    Boolean interrupted,
    String error,
    String errorCode,
    Boolean retryable,
    Double retryAfterMs,
    String podName,
    Double durationMs,
    LLMTokenUsage usage) {

  /** A delta event. */
  public static LlmPodStreamEvent delta(
      String content, List<ToolCallChunk> toolCalls, String id, String name, Map<String, JsonNode> additionalKwargs, Map<String, JsonNode> responseMetadata) {
    return new LlmPodStreamEvent(content, toolCalls, null, id, name, additionalKwargs, responseMetadata, null, null, null, null, null, null, null, null, null);
  }

  /** The closing event of a successful stream. */
  public static LlmPodStreamEvent done(String podName, double durationMs, LLMTokenUsage usage) {
    return new LlmPodStreamEvent(null, null, null, null, null, null, null, true, null, null, null, null, null, podName, durationMs, usage);
  }

  /** The closing event of a failed stream. */
  public static LlmPodStreamEvent failed(String error, String errorCode) {
    return new LlmPodStreamEvent(null, null, null, null, null, null, null, null, null, error, errorCode, null, null, null, null, null);
  }
}
