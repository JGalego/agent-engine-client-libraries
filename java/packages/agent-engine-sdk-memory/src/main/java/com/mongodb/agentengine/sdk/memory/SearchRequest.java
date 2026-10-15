package com.mongodb.agentengine.sdk.memory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A search across one or more memory sources, merged into a single ranked list.
 *
 * @param sources the sources to query; default semantic and episodic
 * @param topK how many chunks to return overall; default 10
 * @param sessionId restricts the episodic leg to one session; it is never inherited from a bound
 *     or ambient context
 * @param domain narrows the taxonomic leg to one domain
 * @param tags narrows the procedural leg to procedures with these tags
 * @param similarityThreshold minimum similarity for the procedural leg; default 0
 * @param metadataFilter metadata filter for the procedural leg
 */
public record SearchRequest(
    String query,
    Set<SearchSource> sources,
    Integer topK,
    String userId,
    String visibility,
    String sessionId,
    String domain,
    List<String> tags,
    Double similarityThreshold,
    Map<String, Object> metadataFilter) {

  public SearchRequest {
    Objects.requireNonNull(query, "query");
    sources = sources == null ? null : Collections.unmodifiableSet(new LinkedHashSet<>(sources));
    tags = tags == null ? null : Collections.unmodifiableList(new ArrayList<>(tags));
    metadataFilter =
        metadataFilter == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(metadataFilter));
  }

  /** Starts a request for the given query. */
  public static Builder builder(String query) {
    return new Builder(query);
  }

  /** Builder for {@link SearchRequest}. */
  public static final class Builder {
    private final String query;
    private Set<SearchSource> sources;
    private Integer topK;
    private String userId;
    private String visibility;
    private String sessionId;
    private String domain;
    private List<String> tags;
    private Double similarityThreshold;
    private Map<String, Object> metadataFilter;

    private Builder(String query) {
      this.query = query;
    }

    public Builder sources(Set<SearchSource> v) { sources = v; return this; }
    public Builder topK(Integer v) { topK = v; return this; }
    public Builder userId(String v) { userId = v; return this; }
    public Builder visibility(String v) { visibility = v; return this; }
    public Builder sessionId(String v) { sessionId = v; return this; }
    public Builder domain(String v) { domain = v; return this; }
    public Builder tags(List<String> v) { tags = v; return this; }
    public Builder similarityThreshold(Double v) { similarityThreshold = v; return this; }
    public Builder metadataFilter(Map<String, Object> v) { metadataFilter = v; return this; }

    public SearchRequest build() {
      return new SearchRequest(
          query, sources, topK, userId, visibility, sessionId, domain, tags, similarityThreshold,
          metadataFilter);
    }
  }
}
