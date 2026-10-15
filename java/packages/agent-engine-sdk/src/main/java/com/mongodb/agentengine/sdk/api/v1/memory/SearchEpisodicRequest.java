package com.mongodb.agentengine.sdk.api.v1.memory;

import java.util.Objects;

/**
 * A ranked search over episodic memories.
 *
 * @param sessionId restricts the search to one session; matched exactly
 * @param topK default 50
 */
public record SearchEpisodicRequest(
    String query,
    String userId,
    String sessionId,
    String visibility,
    Integer topK) {

  public SearchEpisodicRequest {
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
    b.sessionId = sessionId;
    b.visibility = visibility;
    b.topK = topK;
    return b;
  }

  /** Builder for {@link SearchEpisodicRequest}. */
  public static final class Builder {
    private final String query;
    private String userId;
    private String sessionId;
    private String visibility;
    private Integer topK;

    private Builder(String query) {
      this.query = query;
    }

    public Builder userId(String v) { userId = v; return this; }
    public Builder sessionId(String v) { sessionId = v; return this; }
    public Builder visibility(String v) { visibility = v; return this; }
    public Builder topK(Integer v) { topK = v; return this; }

    public SearchEpisodicRequest build() {
      return new SearchEpisodicRequest(query, userId, sessionId, visibility, topK);
    }
  }
}
