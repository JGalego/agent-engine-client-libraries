package com.mongodb.agentengine.sdk.api.v1.memory;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A request to build a unified, token-budgeted memory context.
 *
 * <p>{@code maxTokens} is a gross budget. After retrieval and ranking the server subtracts a
 * 500-token formatting reserve, then greedily selects whole memory chunks that fit in the
 * remainder. A value at or below 500 therefore leaves no room for memories, and a larger value can
 * still yield empty context when no chunk fits. Omit it to keep the server default.
 *
 * @param metadataFilter ignored when empty
 * @param enabledSources sources to draw from; the server defaults to episodic and semantic, so
 *     include {@link MemorySource#STM} for recent turns
 * @param topK default 50
 * @param maxTokens optional gross context-construction budget; omitted keeps the server default
 */
public record BuildContextRequest(
    String query,
    String userId,
    String sessionId,
    String visibility,
    Map<String, Object> metadataFilter,
    Set<MemorySource> enabledSources,
    FormatStyle formatStyle,
    Integer topK,
    Integer maxTokens) {

  public BuildContextRequest {
    Objects.requireNonNull(query, "query");
    if (maxTokens != null && maxTokens <= 0) {
      throw new IllegalArgumentException("maxTokens must be a positive integer");
    }
    metadataFilter = Immutable.map(metadataFilter);
    enabledSources = Immutable.set(enabledSources);
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
    b.metadataFilter = metadataFilter;
    b.enabledSources = enabledSources;
    b.formatStyle = formatStyle;
    b.topK = topK;
    b.maxTokens = maxTokens;
    return b;
  }

  /** Builder for {@link BuildContextRequest}. */
  public static final class Builder {
    private final String query;
    private String userId;
    private String sessionId;
    private String visibility;
    private Map<String, Object> metadataFilter;
    private Set<MemorySource> enabledSources;
    private FormatStyle formatStyle;
    private Integer topK;
    private Integer maxTokens;

    private Builder(String query) {
      this.query = query;
    }

    public Builder userId(String v) { userId = v; return this; }
    public Builder sessionId(String v) { sessionId = v; return this; }
    public Builder visibility(String v) { visibility = v; return this; }
    public Builder metadataFilter(Map<String, Object> v) { metadataFilter = v; return this; }
    public Builder enabledSources(Set<MemorySource> v) { enabledSources = v; return this; }
    public Builder formatStyle(FormatStyle v) { formatStyle = v; return this; }
    public Builder topK(Integer v) { topK = v; return this; }
    public Builder maxTokens(Integer v) { maxTokens = v; return this; }

    public BuildContextRequest build() {
      return new BuildContextRequest(
          query, userId, sessionId, visibility, metadataFilter, enabledSources, formatStyle,
          topK, maxTokens);
    }
  }
}
