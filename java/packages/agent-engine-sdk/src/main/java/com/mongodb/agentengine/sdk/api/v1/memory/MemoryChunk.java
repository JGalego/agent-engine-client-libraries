package com.mongodb.agentengine.sdk.api.v1.memory;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.mongodb.agentengine.sdk.Json;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A retrieved memory, in one shape across every memory type.
 *
 * @param similarityScore relevance on the scale of the retrieval mode that produced the chunk:
 *     vector similarity, text relevance, or the fused rank-fusion score. It is comparable between
 *     chunks retrieved the same way, not across modes. Null when the chunk did not come from a
 *     search.
 */
public record MemoryChunk(
    String id,
    String content,
    MemorySource source,
    @JsonDeserialize(using = Json.LenientInstantDeserializer.class) Instant timestamp,
    List<Double> embedding,
    Double similarityScore,
    Map<String, Object> metadata) {

  public MemoryChunk {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(content, "content");
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(timestamp, "timestamp");
  }
}
