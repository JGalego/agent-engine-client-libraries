package com.mongodb.agentengine.sdk.memory;

import com.mongodb.agentengine.sdk.api.v1.memory.MemorySource;

/**
 * A memory source that {@link Memory#search} can query: every {@link MemorySource} except
 * short-term turns, which are not a search target.
 */
public enum SearchSource {
  SEMANTIC(MemorySource.SEMANTIC),
  EPISODIC(MemorySource.EPISODIC),
  TAXONOMIC(MemorySource.TAXONOMIC),
  PROCEDURAL(MemorySource.PROCEDURAL);

  private final MemorySource source;

  SearchSource(MemorySource source) {
    this.source = source;
  }

  /** The matching memory source. */
  public MemorySource source() {
    return source;
  }
}
