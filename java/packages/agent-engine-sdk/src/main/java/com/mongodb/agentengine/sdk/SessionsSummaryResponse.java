package com.mongodb.agentengine.sdk;

import java.util.List;

/**
 * Response of the session-summary enrichment endpoint.
 *
 * @param sessions one summary per requested session that has framework-side persistence; sessions
 *     without persistence are omitted
 */
public record SessionsSummaryResponse(List<SessionSummary> sessions) {
  public SessionsSummaryResponse {
    sessions = List.copyOf(sessions);
  }
}
