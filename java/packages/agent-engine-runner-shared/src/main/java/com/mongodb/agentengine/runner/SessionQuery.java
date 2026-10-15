package com.mongodb.agentengine.runner;

import com.mongodb.agentengine.sdk.SessionMessagesResponse;
import com.mongodb.agentengine.sdk.SessionsSummaryResponse;
import java.util.List;

/**
 * Read access to the conversation state a framework keeps for its sessions.
 *
 * <p>The runtime serves the platform's session list and history views from this. It takes no
 * workspace identifier on the wire: the orchestration engine only asks about sessions it has
 * already checked belong to the caller. The workspace passed here narrows reads to that
 * workspace's state as a second line of defense.
 */
public interface SessionQuery {

  /** Summarizes the requested sessions. Sessions without stored state are left out. */
  SessionsSummaryResponse summaries(List<String> sessionIds, String workspaceId);

  /** The session's messages, oldest first; empty when nothing is stored. */
  SessionMessagesResponse messages(String sessionId, String workspaceId);
}
