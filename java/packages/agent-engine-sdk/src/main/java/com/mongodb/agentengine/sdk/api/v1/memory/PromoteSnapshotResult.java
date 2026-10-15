package com.mongodb.agentengine.sdk.api.v1.memory;

import java.util.Objects;

/** Outcome of promoting a session snapshot to long-term memory. */
public record PromoteSnapshotResult(
    String snapshotId,
    String sessionId,
    int promotedCount,
    String reason,
    boolean hasEmbedding,
    boolean acknowledged) {

  public PromoteSnapshotResult {
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(reason, "reason");
  }
}
