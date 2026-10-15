package com.mongodb.agentengine.sdk;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Author of a {@link Message}. */
public enum Role {
  @JsonProperty("user")
  USER,
  @JsonProperty("assistant")
  ASSISTANT,
  @JsonProperty("tool")
  TOOL,
  @JsonProperty("system")
  SYSTEM
}
