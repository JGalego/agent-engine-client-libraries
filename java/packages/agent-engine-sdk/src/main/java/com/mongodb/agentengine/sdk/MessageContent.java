package com.mongodb.agentengine.sdk;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Objects;

/**
 * The content of a {@link Message}: a plain string for text-only messages, or a list of content
 * blocks for multimodal content. Both forms share one JSON field.
 */
public sealed interface MessageContent {

  /** Text-only content. Serializes as a JSON string. */
  record Text(@JsonValue String value) implements MessageContent {
    public Text {
      Objects.requireNonNull(value, "value");
    }
  }

  /** Multimodal content. Serializes as a JSON array. */
  record Blocks(@JsonValue List<ContentBlock> blocks) implements MessageContent {
    public Blocks {
      blocks = List.copyOf(blocks);
    }
  }

  @JsonCreator
  static MessageContent fromJson(JsonNode node) {
    if (node.isTextual()) {
      return new Text(node.asText());
    }
    if (node.isArray()) {
      return new Blocks(Json.mapper().convertValue(node, new TypeReference<List<ContentBlock>>() {}));
    }
    throw new IllegalArgumentException("Message content must be a string or an array of blocks");
  }
}
