package com.mongodb.agentengine.sdk.api.v1.memory;

import java.util.Objects;

/**
 * A change to the semantic memory with the given label.
 */
public record UpdateSemanticRequest(
    String label,
    String text,
    String source,
    String visibility) {

  public UpdateSemanticRequest {
    Objects.requireNonNull(label, "label");
  }

  /** Starts a request with its required fields. */
  public static Builder builder(String label) {
    return new Builder(label);
  }

  /** Returns a builder initialized from this request. */
  public Builder toBuilder() {
    Builder b = new Builder(label);
    b.text = text;
    b.source = source;
    b.visibility = visibility;
    return b;
  }

  /** Builder for {@link UpdateSemanticRequest}. */
  public static final class Builder {
    private final String label;
    private String text;
    private String source;
    private String visibility;

    private Builder(String label) {
      this.label = label;
    }

    public Builder text(String v) { text = v; return this; }
    public Builder source(String v) { source = v; return this; }
    public Builder visibility(String v) { visibility = v; return this; }

    public UpdateSemanticRequest build() {
      return new UpdateSemanticRequest(label, text, source, visibility);
    }
  }
}
