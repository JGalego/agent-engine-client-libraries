package com.mongodb.agentengine.sdk.api.v1.memory;

import java.util.Objects;

/** Acknowledgement of a saved session snapshot. */
public record CreateSnapshotResult(
    String id,
    String sessionId,
    int messageCount,
    boolean hasEmbedding,
    String embeddingStrategy,
    String snapshotReason,
    boolean acknowledged) {

  public CreateSnapshotResult {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(snapshotReason, "snapshotReason");
  }
}
