package com.mongodb.agentengine.sdk.api.v1.memory;

import java.util.Objects;

/** Acknowledgement of a saved semantic memory. */
public record CreateSemanticResult(String id, String label, boolean hasEmbedding, boolean acknowledged) {

  public CreateSemanticResult {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(label, "label");
  }
}
