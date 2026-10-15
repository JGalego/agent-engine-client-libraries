package com.mongodb.agentengine.sdk.api.v1.memory;

import java.util.List;
import java.util.Objects;

/**
 * A taxonomic memory (a knowledge-base term) to save.
 *
 * @param userId required by the server; the {@code Memory} facade fills it from the identity
 *     context when omitted
 * @param queryExpansion expand queries with this term; default {@code true}
 * @param visibility default {@code org}
 */
public record CreateTaxonomicRequest(
    String domain,
    String term,
    String definition,
    String userId,
    List<String> relatedTerms,
    Boolean queryExpansion,
    String visibility) {

  public CreateTaxonomicRequest {
    Objects.requireNonNull(domain, "domain");
    Objects.requireNonNull(term, "term");
    Objects.requireNonNull(definition, "definition");
    relatedTerms = Immutable.list(relatedTerms);
  }

  /** Starts a request with its required fields. */
  public static Builder builder(String domain, String term, String definition) {
    return new Builder(domain, term, definition);
  }

  /** Returns a builder initialized from this request. */
  public Builder toBuilder() {
    Builder b = new Builder(domain, term, definition);
    b.userId = userId;
    b.relatedTerms = relatedTerms;
    b.queryExpansion = queryExpansion;
    b.visibility = visibility;
    return b;
  }

  /** Builder for {@link CreateTaxonomicRequest}. */
  public static final class Builder {
    private final String domain;
    private final String term;
    private final String definition;
    private String userId;
    private List<String> relatedTerms;
    private Boolean queryExpansion;
    private String visibility;

    private Builder(String domain, String term, String definition) {
      this.domain = domain;
      this.term = term;
      this.definition = definition;
    }

    public Builder userId(String v) { userId = v; return this; }
    public Builder relatedTerms(List<String> v) { relatedTerms = v; return this; }
    public Builder queryExpansion(Boolean v) { queryExpansion = v; return this; }
    public Builder visibility(String v) { visibility = v; return this; }

    public CreateTaxonomicRequest build() {
      return new CreateTaxonomicRequest(
          domain, term, definition, userId, relatedTerms, queryExpansion, visibility);
    }
  }
}
