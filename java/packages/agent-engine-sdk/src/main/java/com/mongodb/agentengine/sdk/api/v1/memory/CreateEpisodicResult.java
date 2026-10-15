package com.mongodb.agentengine.sdk.api.v1.memory;

import java.util.Objects;

/** Acknowledgement of a saved episodic memory. */
public record CreateEpisodicResult(String id, String title, boolean hasEmbedding, boolean acknowledged) {

  public CreateEpisodicResult {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(title, "title");
  }
}
