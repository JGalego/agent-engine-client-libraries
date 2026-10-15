package com.mongodb.agentengine.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/**
 * Input for an agent execution.
 *
 * @param payload the caller's invocation input, an opaque JSON value ({@code null} is a valid
 *     value; a missing payload is not)
 */
public record AgentInput(JsonNode payload) {
  public AgentInput {
    Objects.requireNonNull(payload, "payload");
  }
}
