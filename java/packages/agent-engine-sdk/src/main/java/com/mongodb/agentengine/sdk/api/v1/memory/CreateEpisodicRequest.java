package com.mongodb.agentengine.sdk.api.v1.memory;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An episodic memory (a conversation summary) to save.
 *
 * @param userId required by the server; the {@code Memory} facade fills it from the identity
 *     context when omitted
 * @param sessionId required by the server; the {@code Memory} facade fills it from the identity
 *     context when omitted
 * @param visibility default {@code private}
 */
public record CreateEpisodicRequest(
    String title,
    String content,
    String summaryText,
    String userId,
    String sessionId,
    String agentId,
    String visibility,
    String snapshotRefId,
    String summaryType,
    String sourceAgent,
    List<String> participants,
    List<String> tags,
    Map<String, Object> metadata,
    String extractionSource,
    List<Double> embedding,
    Boolean skipEmbedding) {

  public CreateEpisodicRequest {
    Objects.requireNonNull(title, "title");
    Objects.requireNonNull(content, "content");
    participants = Immutable.list(participants);
    tags = Immutable.list(tags);
    metadata = Immutable.map(metadata);
    embedding = Immutable.list(embedding);
  }

  /** Starts a request with its required fields. */
  public static Builder builder(String title, String content) {
    return new Builder(title, content);
  }

  /** Returns a builder initialized from this request. */
  public Builder toBuilder() {
    Builder b = new Builder(title, content);
    b.summaryText = summaryText;
    b.userId = userId;
    b.sessionId = sessionId;
    b.agentId = agentId;
    b.visibility = visibility;
    b.snapshotRefId = snapshotRefId;
    b.summaryType = summaryType;
    b.sourceAgent = sourceAgent;
    b.participants = participants;
    b.tags = tags;
    b.metadata = metadata;
    b.extractionSource = extractionSource;
    b.embedding = embedding;
    b.skipEmbedding = skipEmbedding;
    return b;
  }

  /** Builder for {@link CreateEpisodicRequest}. */
  public static final class Builder {
    private final String title;
    private final String content;
    private String summaryText;
    private String userId;
    private String sessionId;
    private String agentId;
    private String visibility;
    private String snapshotRefId;
    private String summaryType;
    private String sourceAgent;
    private List<String> participants;
    private List<String> tags;
    private Map<String, Object> metadata;
    private String extractionSource;
    private List<Double> embedding;
    private Boolean skipEmbedding;

    private Builder(String title, String content) {
      this.title = title;
      this.content = content;
    }

    public Builder summaryText(String v) { summaryText = v; return this; }
    public Builder userId(String v) { userId = v; return this; }
    public Builder sessionId(String v) { sessionId = v; return this; }
    public Builder agentId(String v) { agentId = v; return this; }
    public Builder visibility(String v) { visibility = v; return this; }
    public Builder snapshotRefId(String v) { snapshotRefId = v; return this; }
    public Builder summaryType(String v) { summaryType = v; return this; }
    public Builder sourceAgent(String v) { sourceAgent = v; return this; }
    public Builder participants(List<String> v) { participants = v; return this; }
    public Builder tags(List<String> v) { tags = v; return this; }
    public Builder metadata(Map<String, Object> v) { metadata = v; return this; }
    public Builder extractionSource(String v) { extractionSource = v; return this; }
    public Builder embedding(List<Double> v) { embedding = v; return this; }
    public Builder skipEmbedding(Boolean v) { skipEmbedding = v; return this; }

    public CreateEpisodicRequest build() {
      return new CreateEpisodicRequest(
          title, content, summaryText, userId, sessionId, agentId, visibility, snapshotRefId,
          summaryType, sourceAgent, participants, tags, metadata, extractionSource, embedding,
          skipEmbedding);
    }
  }
}
