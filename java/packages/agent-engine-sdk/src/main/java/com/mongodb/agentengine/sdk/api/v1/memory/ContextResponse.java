package com.mongodb.agentengine.sdk.api.v1.memory;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Objects;

/**
 * The result of a context build.
 *
 * @param formattedContext a JSON string, or an array of message objects, depending on the format
 *     style
 * @param selectedMemories the memories the context was built from; null unless they were requested
 */
public record ContextResponse(
    JsonNode formattedContext, ContextMetadata metadata, List<MemoryChunk> selectedMemories) {

  public ContextResponse {
    Objects.requireNonNull(formattedContext, "formattedContext");
    metadata = metadata == null ? new ContextMetadata(0, null, null) : metadata;
  }
}
