package com.mongodb.agentengine.sdk.api.v1.memory;

import java.util.Objects;

/** Acknowledgement of a saved procedural memory. */
public record CreateProceduralResult(
    String id, String procedure, boolean hasEmbedding, boolean acknowledged) {

  public CreateProceduralResult {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(procedure, "procedure");
  }
}
