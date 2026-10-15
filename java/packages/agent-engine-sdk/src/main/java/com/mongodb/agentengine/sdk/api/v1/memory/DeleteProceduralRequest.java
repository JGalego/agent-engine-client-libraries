package com.mongodb.agentengine.sdk.api.v1.memory;

/**
 * A deletion of a procedural memory, addressed by id or by procedure name.
 *
 * @param soft mark as deleted instead of removing; default {@code true}
 */
public record DeleteProceduralRequest(
    String id,
    String procedure,
    Boolean soft) {

  /** Starts an empty request. */
  public static Builder builder() {
    return new Builder();
  }

  /** Returns a builder initialized from this request. */
  public Builder toBuilder() {
    Builder b = new Builder();
    b.id = id;
    b.procedure = procedure;
    b.soft = soft;
    return b;
  }

  /** Builder for {@link DeleteProceduralRequest}. */
  public static final class Builder {
    private String id;
    private String procedure;
    private Boolean soft;

    private Builder() {}

    public Builder id(String v) { id = v; return this; }
    public Builder procedure(String v) { procedure = v; return this; }
    public Builder soft(Boolean v) { soft = v; return this; }

    public DeleteProceduralRequest build() {
      return new DeleteProceduralRequest(id, procedure, soft);
    }
  }
}
