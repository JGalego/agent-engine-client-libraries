package com.mongodb.agentengine.sdk.api.v1.memory;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A conversation turn to append to short-term memory.
 *
 * @param role who authored the turn, for example {@code user} or {@code assistant}
 * @param sessionId the session; none records a sessionless turn
 * @param isError whether a tool turn reports a failure; default {@code false}
 * @param idempotencyKey server-side dedupe key; a retried write with the same key commits at most
 *     once
 */
public record WriteTurnRequest(
    String role,
    String content,
    String userId,
    String sessionId,
    Integer tokens,
    String stopReason,
    String modelName,
    List<Map<String, Object>> toolCalls,
    String toolCallId,
    String toolName,
    Boolean isError,
    String idempotencyKey) {

  public WriteTurnRequest {
    Objects.requireNonNull(role, "role");
    toolCalls = Immutable.list(toolCalls);
  }

  /** Starts a request with its required fields. */
  public static Builder builder(String role) {
    return new Builder(role);
  }

  /** Returns a builder initialized from this request. */
  public Builder toBuilder() {
    Builder b = new Builder(role);
    b.content = content;
    b.userId = userId;
    b.sessionId = sessionId;
    b.tokens = tokens;
    b.stopReason = stopReason;
    b.modelName = modelName;
    b.toolCalls = toolCalls;
    b.toolCallId = toolCallId;
    b.toolName = toolName;
    b.isError = isError;
    b.idempotencyKey = idempotencyKey;
    return b;
  }

  /** Builder for {@link WriteTurnRequest}. */
  public static final class Builder {
    private final String role;
    private String content;
    private String userId;
    private String sessionId;
    private Integer tokens;
    private String stopReason;
    private String modelName;
    private List<Map<String, Object>> toolCalls;
    private String toolCallId;
    private String toolName;
    private Boolean isError;
    private String idempotencyKey;

    private Builder(String role) {
      this.role = role;
    }

    public Builder content(String v) { content = v; return this; }
    public Builder userId(String v) { userId = v; return this; }
    public Builder sessionId(String v) { sessionId = v; return this; }
    public Builder tokens(Integer v) { tokens = v; return this; }
    public Builder stopReason(String v) { stopReason = v; return this; }
    public Builder modelName(String v) { modelName = v; return this; }
    public Builder toolCalls(List<Map<String, Object>> v) { toolCalls = v; return this; }
    public Builder toolCallId(String v) { toolCallId = v; return this; }
    public Builder toolName(String v) { toolName = v; return this; }
    public Builder isError(Boolean v) { isError = v; return this; }
    public Builder idempotencyKey(String v) { idempotencyKey = v; return this; }

    public WriteTurnRequest build() {
      return new WriteTurnRequest(
          role, content, userId, sessionId, tokens, stopReason, modelName, toolCalls,
          toolCallId, toolName, isError, idempotencyKey);
    }
  }
}
