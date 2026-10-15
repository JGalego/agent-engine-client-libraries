package com.mongodb.agentengine.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/**
 * Output from an agent execution.
 *
 * @param response the agent's result, an opaque JSON value
 */
public record AgentOutput(JsonNode response) {
  public AgentOutput {
    Objects.requireNonNull(response, "response");
  }
}
