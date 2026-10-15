package com.mongodb.agentengine.sdk.api.v1.memory;

import java.util.List;
import java.util.Objects;

/** Outcome of saving several semantic memories at once. */
public record BulkCreateSemanticResult(
    int createdCount,
    int skippedCount,
    List<String> createdIds,
    List<String> skippedLabels,
    boolean hasEmbeddings,
    boolean acknowledged) {

  public BulkCreateSemanticResult {
    Objects.requireNonNull(createdIds, "createdIds");
    Objects.requireNonNull(skippedLabels, "skippedLabels");
  }
}
