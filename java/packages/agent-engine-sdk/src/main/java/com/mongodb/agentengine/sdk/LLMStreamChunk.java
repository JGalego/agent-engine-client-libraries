package com.mongodb.agentengine.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

/**
 * One chunk of a streamed LLM response. Every field is optional because chunks carry different
 * data: text deltas during generation, partial tool calls as they build up, and token usage at
 * the end of the stream.
 */
public record LLMStreamChunk(
    String content,
    List<ToolCallChunk> toolCalls,
    LLMTokenUsage usage,
    String id,
    String name,
    Map<String, JsonNode> additionalKwargs,
    Map<String, JsonNode> responseMetadata) {}
