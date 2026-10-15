package com.mongodb.agentengine.sdk.api.v1.memory;

import java.util.Objects;

/** Acknowledgement of a saved user-context entry. */
public record CreateUserContextResult(String id, String contextKey, boolean acknowledged) {

  public CreateUserContextResult {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(contextKey, "contextKey");
  }
}
