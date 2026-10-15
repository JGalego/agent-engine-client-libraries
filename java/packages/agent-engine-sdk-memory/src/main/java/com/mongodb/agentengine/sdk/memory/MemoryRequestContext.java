package com.mongodb.agentengine.sdk.memory;

/**
 * Identity carried across a memory call. Any field may be null.
 *
 * @param userId the end user whose memories are read and written
 * @param agentId the agent acting on the user's behalf
 * @param sessionId the conversation session
 */
public record MemoryRequestContext(String userId, String agentId, String sessionId) {

  /** A context with no identity. */
  public static final MemoryRequestContext EMPTY = new MemoryRequestContext(null, null, null);

  /** Returns a context with the given user and session, and no agent. */
  public static MemoryRequestContext of(String userId, String sessionId) {
    return new MemoryRequestContext(userId, null, sessionId);
  }
}
