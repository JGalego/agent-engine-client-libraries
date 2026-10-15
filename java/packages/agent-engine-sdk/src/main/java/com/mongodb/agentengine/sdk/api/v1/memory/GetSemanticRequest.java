package com.mongodb.agentengine.sdk.api.v1.memory;

/**
 * A lookup of one semantic memory, by label or by id.
 */
public record GetSemanticRequest(
    String label,
    String id,
    String userId,
    String visibility) {

  /** Starts an empty request. */
  public static Builder builder() {
    return new Builder();
  }

  /** Returns a builder initialized from this request. */
  public Builder toBuilder() {
    Builder b = new Builder();
    b.label = label;
    b.id = id;
    b.userId = userId;
    b.visibility = visibility;
    return b;
  }

  /** Builder for {@link GetSemanticRequest}. */
  public static final class Builder {
    private String label;
    private String id;
    private String userId;
    private String visibility;

    private Builder() {}

    public Builder label(String v) { label = v; return this; }
    public Builder id(String v) { id = v; return this; }
    public Builder userId(String v) { userId = v; return this; }
    public Builder visibility(String v) { visibility = v; return this; }

    public GetSemanticRequest build() {
      return new GetSemanticRequest(label, id, userId, visibility);
    }
  }
}
