package com.mongodb.agentengine.sdk.api.v1.memory;

import java.util.Map;
import java.util.Objects;

/**
 * A semantic query over memories of one custom type.
 *
 * @param memoryType the declared type name
 * @param tags exact-match filters on declared tag keys
 * @param topK default 10
 */
public record RetrieveCustomRequest(
    String memoryType,
    String query,
    Map<String, Object> tags,
    Integer topK) {

  public RetrieveCustomRequest {
    Objects.requireNonNull(memoryType, "memoryType");
    Objects.requireNonNull(query, "query");
    tags = Immutable.map(tags);
  }

  /** Starts a request with its required fields. */
  public static Builder builder(String memoryType, String query) {
    return new Builder(memoryType, query);
  }

  /** Returns a builder initialized from this request. */
  public Builder toBuilder() {
    Builder b = new Builder(memoryType, query);
    b.tags = tags;
    b.topK = topK;
    return b;
  }

  /** Builder for {@link RetrieveCustomRequest}. */
  public static final class Builder {
    private final String memoryType;
    private final String query;
    private Map<String, Object> tags;
    private Integer topK;

    private Builder(String memoryType, String query) {
      this.memoryType = memoryType;
      this.query = query;
    }

    public Builder tags(Map<String, Object> v) { tags = v; return this; }
    public Builder topK(Integer v) { topK = v; return this; }

    public RetrieveCustomRequest build() {
      return new RetrieveCustomRequest(memoryType, query, tags, topK);
    }
  }
}
