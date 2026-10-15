package com.mongodb.agentengine.sdk.api.v1.memory;

/**
 * A listing of episodic memories.
 *
 * @param sessionId restricts the listing to one session
 * @param limit default 20
 */
public record ListEpisodicRequest(
    String userId,
    String sessionId,
    String visibility,
    Integer limit) {

  /** Starts an empty request. */
  public static Builder builder() {
    return new Builder();
  }

  /** Returns a builder initialized from this request. */
  public Builder toBuilder() {
    Builder b = new Builder();
    b.userId = userId;
    b.sessionId = sessionId;
    b.visibility = visibility;
    b.limit = limit;
    return b;
  }

  /** Builder for {@link ListEpisodicRequest}. */
  public static final class Builder {
    private String userId;
    private String sessionId;
    private String visibility;
    private Integer limit;

    private Builder() {}

    public Builder userId(String v) { userId = v; return this; }
    public Builder sessionId(String v) { sessionId = v; return this; }
    public Builder visibility(String v) { visibility = v; return this; }
    public Builder limit(Integer v) { limit = v; return this; }

    public ListEpisodicRequest build() {
      return new ListEpisodicRequest(userId, sessionId, visibility, limit);
    }
  }
}
