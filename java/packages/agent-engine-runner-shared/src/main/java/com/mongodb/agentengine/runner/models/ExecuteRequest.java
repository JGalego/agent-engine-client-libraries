package com.mongodb.agentengine.runner.models;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.Objects;

/**
 * A request from the orchestration engine to run one agent turn.
 *
 * @param executionId unique id of the execution
 * @param message the user message; may instead arrive as {@code payload.message}
 * @param platformApiUrl the OE URL the request names; the runner prefers its own configured URL
 * @param suspendGeneration the suspension generation this dispatch expects, if any
 * @param resume whether this resumes a suspended execution
 * @param resumeData the value the execution is resumed with
 * @param userId the calling user
 * @param sessionId the conversation session; defaults to the execution id
 * @param workspaceId the workspace of the executing agent
 * @param customHeaders caller-provided headers, prefix-stripped and lower-cased
 * @param payload opaque caller input forwarded to the agent
 * @param metadata opaque framework-owned state, passed back unchanged on resume
 * @param previousExecutionCancelled whether the session's prior execution was cancelled mid-run
 */
public record ExecuteRequest(
    String platformTraceId,
    String executionId,
    String message,
    String platformApiUrl,
    Integer suspendGeneration,
    String platformApiOwnerUrl,
    boolean resume,
    Integer resumeFromStep,
    JsonNode resumeData,
    String orgId,
    String userId,
    String sessionId,
    String workspaceId,
    String projectId,
    String rootSessionId,
    String rootExecutionId,
    Map<String, String> customHeaders,
    JsonNode payload,
    Map<String, JsonNode> metadata,
    boolean previousExecutionCancelled) {

  public ExecuteRequest {
    Objects.requireNonNull(executionId, "execution_id");
    Objects.requireNonNull(platformApiUrl, "platform_api_url");
    message = message == null ? "" : message;
  }

  /** The session the turn belongs to: the request's, else the execution's own. */
  public String effectiveSessionId() {
    return sessionId != null && !sessionId.isEmpty() ? sessionId : executionId;
  }

  /** The text of {@code payload.message}, or null when it is absent or not a string. */
  public String payloadMessage() {
    if (payload == null || !payload.isObject()) {
      return null;
    }
    JsonNode value = payload.get("message");
    return value != null && value.isTextual() ? value.asText() : null;
  }
}
