package com.mongodb.agentengine.sdk.api.v1.memory;

import java.util.Objects;

/** Acknowledgement of a recorded conversation turn. */
public record WriteTurnResult(String id, String sessionId, long turnSeq, boolean acknowledged) {

  public WriteTurnResult {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(sessionId, "sessionId");
  }
}
