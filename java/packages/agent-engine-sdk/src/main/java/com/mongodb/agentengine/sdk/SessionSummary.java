package com.mongodb.agentengine.sdk;

import java.util.Objects;

/**
 * Per-session summary sourced from framework-specific persistence.
 *
 * <p>The runtime's session-enrichment endpoint returns it, and the orchestration engine merges it
 * with its own tenant fields when assembling the public sessions list.
 *
 * @param sessionId identifier of the session
 * @param lastActivity ISO 8601 timestamp of the most recent activity; empty when unavailable
 * @param createdAt ISO 8601 timestamp when the session was first persisted; empty when unavailable
 * @param messageCount number of messages, the same count the session messages endpoint returns
 * @param firstMessagePreview first human-authored message truncated to 80 characters; empty when
 *     none has been persisted
 */
public record SessionSummary(
    String sessionId,
    String lastActivity,
    String createdAt,
    int messageCount,
    String firstMessagePreview) {

  public SessionSummary {
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(lastActivity, "lastActivity");
    Objects.requireNonNull(createdAt, "createdAt");
    firstMessagePreview = firstMessagePreview == null ? "" : firstMessagePreview;
  }
}
