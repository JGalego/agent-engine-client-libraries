package com.mongodb.agentengine.runner.models;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.Objects;

/**
 * A request from the orchestration engine to run a remote tool on the tool server.
 *
 * @param sessionId the conversation session, for tools that use memory
 * @param payload the caller's invocation input, forwarded so tools can read it
 */
public record ToolPodExecuteRequest(
    String executionId,
    String toolName,
    Map<String, Object> arguments,
    String toolCallId,
    Integer stepNumber,
    String sessionId,
    String userId,
    String oeUrl,
    Map<String, String> customHeaders,
    JsonNode payload,
    Map<String, Object> metadata) {

  public ToolPodExecuteRequest {
    Objects.requireNonNull(executionId, "execution_id");
    Objects.requireNonNull(toolName, "tool_name");
    arguments = arguments == null ? Map.of() : arguments;
  }
}
