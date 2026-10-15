package com.mongodb.agentengine.sdk.memory;

/** An identity dimension of a {@link MemoryRequestContext}. */
public enum IdentityField {
  USER_ID("userId"),
  AGENT_ID("agentId"),
  SESSION_ID("sessionId");

  private final String label;

  IdentityField(String label) {
    this.label = label;
  }

  String label() {
    return label;
  }
}
