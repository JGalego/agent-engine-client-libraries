package com.mongodb.agentengine.sdk.api.v1.memory;

/**
 * A lookup of one procedural memory, by id or by procedure name.
 *
 * @param includeDeleted default {@code false}
 */
public record GetProceduralRequest(
    String id,
    String procedure,
    String userId,
    String visibility,
    Boolean includeDeleted) {

  /** Starts an empty request. */
  public static Builder builder() {
    return new Builder();
  }

  /** Returns a builder initialized from this request. */
  public Builder toBuilder() {
    Builder b = new Builder();
    b.id = id;
    b.procedure = procedure;
    b.userId = userId;
    b.visibility = visibility;
    b.includeDeleted = includeDeleted;
    return b;
  }

  /** Builder for {@link GetProceduralRequest}. */
  public static final class Builder {
    private String id;
    private String procedure;
    private String userId;
    private String visibility;
    private Boolean includeDeleted;

    private Builder() {}

    public Builder id(String v) { id = v; return this; }
    public Builder procedure(String v) { procedure = v; return this; }
    public Builder userId(String v) { userId = v; return this; }
    public Builder visibility(String v) { visibility = v; return this; }
    public Builder includeDeleted(Boolean v) { includeDeleted = v; return this; }

    public GetProceduralRequest build() {
      return new GetProceduralRequest(id, procedure, userId, visibility, includeDeleted);
    }
  }
}
