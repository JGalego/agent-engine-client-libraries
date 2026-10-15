package com.mongodb.agentengine.sdk.api.v1.memory;

import java.util.Objects;

/**
 * A ranked search over taxonomic memories. Taxonomic memory is org-scoped, so there is no user
 * filter.
 *
 * @param topK default 50
 */
public record SearchTaxonomicRequest(
    String query,
    String domain,
    String visibility,
    Integer topK) {

  public SearchTaxonomicRequest {
    Objects.requireNonNull(query, "query");
  }

  /** Starts a request with its required fields. */
  public static Builder builder(String query) {
    return new Builder(query);
  }

  /** Returns a builder initialized from this request. */
  public Builder toBuilder() {
    Builder b = new Builder(query);
    b.domain = domain;
    b.visibility = visibility;
    b.topK = topK;
    return b;
  }

  /** Builder for {@link SearchTaxonomicRequest}. */
  public static final class Builder {
    private final String query;
    private String domain;
    private String visibility;
    private Integer topK;

    private Builder(String query) {
      this.query = query;
    }

    public Builder domain(String v) { domain = v; return this; }
    public Builder visibility(String v) { visibility = v; return this; }
    public Builder topK(Integer v) { topK = v; return this; }

    public SearchTaxonomicRequest build() {
      return new SearchTaxonomicRequest(query, domain, visibility, topK);
    }
  }
}
