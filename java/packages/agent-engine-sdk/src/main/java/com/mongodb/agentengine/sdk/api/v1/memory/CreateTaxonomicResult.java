package com.mongodb.agentengine.sdk.api.v1.memory;

import java.util.Objects;

/** Acknowledgement of a saved taxonomic memory. */
public record CreateTaxonomicResult(
    String id, String domain, String term, boolean hasEmbedding, boolean acknowledged) {

  public CreateTaxonomicResult {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(domain, "domain");
    Objects.requireNonNull(term, "term");
  }
}
