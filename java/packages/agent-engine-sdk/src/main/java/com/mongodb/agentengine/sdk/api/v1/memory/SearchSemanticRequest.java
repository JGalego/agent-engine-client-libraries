package com.mongodb.agentengine.sdk.api.v1.memory;

import java.util.Objects;

/**
 * A ranked search over semantic memories.
 *
 * @param topK default 50
 */
public record SearchSemanticRequest(
    String query,
    String userId,
    String visibility,
    Integer topK) {

  public SearchSemanticRequest {
    Objects.requireNonNull(query, "query");
  }

  /** Starts a request with its required fields. */
  public static Builder builder(String query) {
    return new Builder(query);
  }

  /** Returns a builder initialized from this request. */
  public Builder toBuilder() {
    Builder b = new Builder(query);
    b.userId = userId;
    b.visibility = visibility;
    b.topK = topK;
    return b;
  }

  /** Builder for {@link SearchSemanticRequest}. */
  public static final class Builder {
    private final String query;
    private String userId;
    private String visibility;
    private Integer topK;

    private Builder(String query) {
      this.query = query;
    }

    public Builder userId(String v) { userId = v; return this; }
    public Builder visibility(String v) { visibility = v; return this; }
    public Builder topK(Integer v) { topK = v; return this; }

    public SearchSemanticRequest build() {
      return new SearchSemanticRequest(query, userId, visibility, topK);
    }
  }
}
