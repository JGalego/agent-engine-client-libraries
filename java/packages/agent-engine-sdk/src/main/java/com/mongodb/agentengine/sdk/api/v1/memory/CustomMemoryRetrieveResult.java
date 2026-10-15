package com.mongodb.agentengine.sdk.api.v1.memory;

import java.util.List;
import java.util.Objects;

/** Memories of a custom type matching a query. */
public record CustomMemoryRetrieveResult(List<RetrievedCustomMemory> results, int count) {

  public CustomMemoryRetrieveResult {
    Objects.requireNonNull(results, "results");
  }
}
