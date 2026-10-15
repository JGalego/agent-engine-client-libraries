package com.mongodb.agentengine.sdk.api.v1.memory;

import java.util.Objects;

/**
 * Outcome of writing a session's internal state.
 *
 * @param wasNoop whether the write changed nothing because the content hash matched
 */
public record InternalStateResult(
    String sessionId,
    int version,
    int tokenCount,
    String contentHash,
    Integer previousVersion,
    boolean wasNoop,
    boolean acknowledged) {

  public InternalStateResult {
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(contentHash, "contentHash");
  }
}
