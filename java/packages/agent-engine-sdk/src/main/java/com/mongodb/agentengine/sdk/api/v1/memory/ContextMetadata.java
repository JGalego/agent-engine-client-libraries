package com.mongodb.agentengine.sdk.api.v1.memory;

import java.util.Map;

/**
 * Metadata about a context build.
 *
 * @param tokenCount tokens in the formatted output, excluding the formatting reserve
 * @param memoryCounts memories selected per source
 * @param timing time spent per retrieval stage
 */
public record ContextMetadata(
    int tokenCount, Map<String, Integer> memoryCounts, Map<String, Double> timing) {

  public ContextMetadata {
    memoryCounts = memoryCounts == null ? Map.of() : memoryCounts;
    timing = timing == null ? Map.of() : timing;
  }
}
