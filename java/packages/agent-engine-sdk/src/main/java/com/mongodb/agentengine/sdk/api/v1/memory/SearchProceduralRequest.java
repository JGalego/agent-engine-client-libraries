package com.mongodb.agentengine.sdk.api.v1.memory;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A ranked search over procedural memories.
 *
 * @param topK default 10 for discovery, 50 for chunk retrieval
 * @param similarityThreshold minimum similarity for discovery; default 0
 * @param metadataFilter ignored when empty
 */
public record SearchProceduralRequest(
    String query,
    String userId,
    String visibility,
    List<String> tags,
    Integer topK,
    Double similarityThreshold,
    Map<String, Object> metadataFilter) {

  public SearchProceduralRequest {
    Objects.requireNonNull(query, "query");
    tags = Immutable.list(tags);
    metadataFilter = Immutable.map(metadataFilter);
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
    b.tags = tags;
    b.topK = topK;
    b.similarityThreshold = similarityThreshold;
    b.metadataFilter = metadataFilter;
    return b;
  }

  /** Builder for {@link SearchProceduralRequest}. */
  public static final class Builder {
    private final String query;
    private String userId;
    private String visibility;
    private List<String> tags;
    private Integer topK;
    private Double similarityThreshold;
    private Map<String, Object> metadataFilter;

    private Builder(String query) {
      this.query = query;
    }

    public Builder userId(String v) { userId = v; return this; }
    public Builder visibility(String v) { visibility = v; return this; }
    public Builder tags(List<String> v) { tags = v; return this; }
    public Builder topK(Integer v) { topK = v; return this; }
    public Builder similarityThreshold(Double v) { similarityThreshold = v; return this; }
    public Builder metadataFilter(Map<String, Object> v) { metadataFilter = v; return this; }

    public SearchProceduralRequest build() {
      return new SearchProceduralRequest(
          query, userId, visibility, tags, topK, similarityThreshold, metadataFilter);
    }
  }
}
