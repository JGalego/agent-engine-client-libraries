package com.mongodb.agentengine.sdk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

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

  /**
   * Folds the chunks of a streamed response into the final response.
   *
   * <p>Text deltas are concatenated; tool-call fragments are merged per call and their argument
   * strings parsed as JSON when possible; the last usage wins; and metadata maps merge, later
   * chunks overriding earlier ones.
   */
  public static LLMResponse fromChunks(List<LLMStreamChunk> chunks) {
    StringBuilder content = new StringBuilder();
    List<ToolCallChunk> fragments = new ArrayList<>();
    LLMTokenUsage usage = null;
    String id = null;
    String name = null;
    Map<String, JsonNode> responseMetadata = null;
    Map<String, JsonNode> additionalKwargs = null;

    for (LLMStreamChunk chunk : chunks) {
      if (chunk.content() != null) {
        content.append(chunk.content());
      }
      if (chunk.toolCalls() != null) {
        fragments.addAll(chunk.toolCalls());
      }
      if (chunk.usage() != null) {
        usage = chunk.usage();
      }
      if (chunk.id() != null) {
        id = chunk.id();
      }
      if (chunk.name() != null) {
        name = chunk.name();
      }
      if (chunk.responseMetadata() != null) {
        responseMetadata = merged(responseMetadata, chunk.responseMetadata());
      }
      if (chunk.additionalKwargs() != null) {
        additionalKwargs = merged(additionalKwargs, chunk.additionalKwargs());
      }
    }
    Map<String, JsonNode> metadata =
        usage == null ? null : Json.mapper().convertValue(usage, new TypeReference<Map<String, JsonNode>>() {});
    return new LLMResponse(content.toString(), toolCalls(fragments), metadata, usage, id, name, additionalKwargs, responseMetadata);
  }

  private static Map<String, JsonNode> merged(Map<String, JsonNode> base, Map<String, JsonNode> overlay) {
    Map<String, JsonNode> result = new LinkedHashMap<>(base == null ? Map.of() : base);
    result.putAll(overlay);
    return result;
  }

  /**
   * Assembles complete tool calls from streamed fragments. A fragment belongs to the call with its
   * {@code index}; failing that, to the call with its {@code id}; failing that, to the call the
   * previous fragment extended. Argument fragments are concatenated and then parsed as JSON.
   */
  private static List<LLMToolCall> toolCalls(List<ToolCallChunk> fragments) {
    if (fragments.isEmpty()) {
      return null;
    }
    Map<Integer, Map<String, Object>> calls = new TreeMap<>();
    Map<String, Integer> indexById = new HashMap<>();
    Integer current = null;
    int next = 0;
    for (ToolCallChunk fragment : fragments) {
      int index;
      if (fragment.index() != null) {
        index = fragment.index();
        next = Math.max(next, index + 1);
      } else if (fragment.id() != null) {
        Integer known = indexById.get(fragment.id());
        index = known != null ? known : next++;
      } else if (current != null) {
        index = current;
      } else {
        index = next++;
      }
      current = index;
      Map<String, Object> call = calls.computeIfAbsent(index, i -> new HashMap<>());
      if (fragment.index() != null) {
        call.put("index", fragment.index());
      }
      if (fragment.id() != null) {
        call.put("id", fragment.id());
        indexById.put(fragment.id(), index);
      }
      if (fragment.name() != null) {
        call.put("name", fragment.name());
      }
      if (fragment.type() != null) {
        call.put("type", fragment.type());
      }
      if (fragment.args() != null) {
        call.merge("args", fragment.args(), (previous, added) -> previous + (String) added);
      }
    }
    List<LLMToolCall> result = new ArrayList<>();
    for (Map<String, Object> call : calls.values()) {
      result.add(
          new LLMToolCall(
              (String) call.get("id"),
              (String) call.get("name"),
              parseArguments((String) call.get("args")),
              (String) call.get("type"),
              (Integer) call.get("index")));
    }
    return result;
  }

  /** Parses accumulated argument text as JSON, keeping it as a string when it is not valid JSON. */
  private static JsonNode parseArguments(String text) {
    if (text == null) {
      return null;
    }
    try {
      return Json.mapper().readTree(text);
    } catch (JsonProcessingException e) {
      return JsonNodeFactory.instance.textNode(text);
    }
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
