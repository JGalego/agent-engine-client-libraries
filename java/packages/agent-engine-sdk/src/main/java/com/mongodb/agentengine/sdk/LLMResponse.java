package com.mongodb.agentengine.sdk;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Framework-neutral LLM response.
 *
 * @param content the generated text
 * @param toolCalls tool calls the model requested
 * @param metadata provider metadata
 * @param usage token usage, when the provider reports it
 */
public record LLMResponse(
    String content,
    List<LLMToolCall> toolCalls,
    Map<String, JsonNode> metadata,
    LLMTokenUsage usage,
    String id,
    String name,
    Map<String, JsonNode> additionalKwargs,
    Map<String, JsonNode> responseMetadata) {

  private static final Set<String> TOKEN_KEYS =
      Set.of(
          "input_tokens", "output_tokens", "prompt_tokens", "completion_tokens", "total_tokens",
          "model");

  public LLMResponse {
    content = content == null ? "" : content;
    metadata = metadata == null ? Map.of() : metadata;
  }

  /**
   * Builds a response from a raw provider payload, taking usage from a top-level {@code usage}
   * object or, failing that, from token keys embedded in {@code metadata}.
   */
  public static LLMResponse fromRaw(JsonNode raw) {
    var mapper = Json.mapper();
    JsonNode usageNode = raw.path("usage");
    JsonNode metadata = raw.path("metadata");
    if (usageNode.isMissingNode() || usageNode.isNull()) {
      usageNode = null;
      if (metadata.isObject()) {
        JsonNode nested = metadata.path("usage");
        if (nested.isObject()) {
          usageNode = nested;
        } else if (metadata.properties().stream().anyMatch(e -> TOKEN_KEYS.contains(e.getKey()))) {
          usageNode = metadata;
        }
      }
    }
    return new LLMResponse(
        raw.path("content").asText(""),
        mapper.convertValue(either(raw, "tool_calls", "toolCalls"), new TypeReference<>() {}),
        metadata.isObject() ? mapper.convertValue(metadata, new TypeReference<>() {}) : null,
        usageNode == null ? null : mapper.convertValue(usageNode, LLMTokenUsage.class),
        text(raw, "id"),
        text(raw, "name"),
        mapper.convertValue(either(raw, "additional_kwargs", "additionalKwargs"), new TypeReference<>() {}),
        mapper.convertValue(either(raw, "response_metadata", "responseMetadata"), new TypeReference<>() {}));
  }

  private static JsonNode either(JsonNode raw, String wireName, String camelName) {
    JsonNode value = raw.path(wireName);
    return value.isMissingNode() ? raw.path(camelName) : value;
  }

  private static String text(JsonNode raw, String field) {
    JsonNode value = raw.path(field);
    return value.isTextual() ? value.asText() : null;
  }
}
