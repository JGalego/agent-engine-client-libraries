package com.mongodb.agentengine.sdk.api.v1.memory;

import java.util.Map;
import java.util.Objects;

/**
 * Acknowledgement of a saved custom-type memory.
 *
 * @param tags the stored tags; values are strings, numbers or booleans
 */
public record CustomMemorySaveResult(
    String id, String type, Map<String, Object> tags, boolean hasEmbedding) {

  public CustomMemorySaveResult {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(tags, "tags");
  }
}
