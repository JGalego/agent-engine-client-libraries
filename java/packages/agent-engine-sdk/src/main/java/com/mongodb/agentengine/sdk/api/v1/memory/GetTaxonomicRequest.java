package com.mongodb.agentengine.sdk.api.v1.memory;

/**
 * A lookup of taxonomic entries, optionally narrowed to one term.
 */
public record GetTaxonomicRequest(
    String domain,
    String term,
    String userId,
    String visibility) {

  /** Starts an empty request. */
  public static Builder builder() {
    return new Builder();
  }

  /** Returns a builder initialized from this request. */
  public Builder toBuilder() {
    Builder b = new Builder();
    b.domain = domain;
    b.term = term;
    b.userId = userId;
    b.visibility = visibility;
    return b;
  }

  /** Builder for {@link GetTaxonomicRequest}. */
  public static final class Builder {
    private String domain;
    private String term;
    private String userId;
    private String visibility;

    private Builder() {}

    public Builder domain(String v) { domain = v; return this; }
    public Builder term(String v) { term = v; return this; }
    public Builder userId(String v) { userId = v; return this; }
    public Builder visibility(String v) { visibility = v; return this; }

    public GetTaxonomicRequest build() {
      return new GetTaxonomicRequest(domain, term, userId, visibility);
    }
  }
}
