package com.mongodb.agentengine.sdk;

import java.util.List;

/**
 * Response of the session-messages endpoint.
 *
 * @param messages the decoded conversation history in chronological order; empty when the session
 *     has no persisted messages
 */
public record SessionMessagesResponse(List<SessionMessage> messages) {
  public SessionMessagesResponse {
    messages = List.copyOf(messages);
  }
}
