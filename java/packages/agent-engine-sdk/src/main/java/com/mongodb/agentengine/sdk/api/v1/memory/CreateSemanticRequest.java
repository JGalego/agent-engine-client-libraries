package com.mongodb.agentengine.sdk.api.v1.memory;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A semantic memory (a fact) to save.
 *
 * @param label the fact's unique name
 * @param userId required by the server; the {@code Memory} facade fills it from the identity
 *     context when omitted
 * @param source default {@code agent}
 * @param visibility default {@code private}
 * @param embedding a precomputed embedding, if the caller has one
 * @param upsert update an existing memory with the same label instead of failing; default {@code
 *     false}
 */
public record CreateSemanticRequest(
    String label,
    String text,
    String userId,
    String agentId,
    String source,
    String visibility,
    List<Double> embedding,
    Map<String, Object> metadata,
    Boolean upsert) {

  public CreateSemanticRequest {
    Objects.requireNonNull(label, "label");
    Objects.requireNonNull(text, "text");
    embedding = Immutable.list(embedding);
    metadata = Immutable.map(metadata);
  }

  /** Starts a request with its required fields. */
  public static Builder builder(String label, String text) {
    return new Builder(label, text);
  }

  /** Returns a builder initialized from this request. */
  public Builder toBuilder() {
    Builder b = new Builder(label, text);
    b.userId = userId;
    b.agentId = agentId;
    b.source = source;
    b.visibility = visibility;
    b.embedding = embedding;
    b.metadata = metadata;
    b.upsert = upsert;
    return b;
  }

  /** Builder for {@link CreateSemanticRequest}. */
  public static final class Builder {
    private final String label;
    private final String text;
    private String userId;
    private String agentId;
    private String source;
    private String visibility;
    private List<Double> embedding;
    private Map<String, Object> metadata;
    private Boolean upsert;

    private Builder(String label, String text) {
      this.label = label;
      this.text = text;
    }

    public Builder userId(String v) { userId = v; return this; }
    public Builder agentId(String v) { agentId = v; return this; }
    public Builder source(String v) { source = v; return this; }
    public Builder visibility(String v) { visibility = v; return this; }
    public Builder embedding(List<Double> v) { embedding = v; return this; }
    public Builder metadata(Map<String, Object> v) { metadata = v; return this; }
    public Builder upsert(Boolean v) { upsert = v; return this; }

    public CreateSemanticRequest build() {
      return new CreateSemanticRequest(
          label, text, userId, agentId, source, visibility, embedding, metadata, upsert);
    }
  }
}
