package com.mongodb.agentengine.sdk.api.v1.memory;

import java.util.Map;
import java.util.Objects;

/**
 * A custom-type memory returned by retrieval.
 *
 * @param tags the stored tags; values are strings, numbers or booleans
 */
public record RetrievedCustomMemory(
    String id,
    String type,
    String content,
    Map<String, Object> tags,
    Map<String, Object> contextualMetadata,
    String userId,
    String agentId) {

  public RetrievedCustomMemory {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(content, "content");
    Objects.requireNonNull(tags, "tags");
  }
}
