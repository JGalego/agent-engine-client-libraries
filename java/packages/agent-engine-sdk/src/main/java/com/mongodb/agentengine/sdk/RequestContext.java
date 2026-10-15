package com.mongodb.agentengine.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/**
 * Request-scoped context passed to every agent execution.
 *
 * <p>It carries execution identity and platform plumbing. The caller's invocation input lives in
 * {@link AgentInput#payload()}, not here. Agents stop a cancelled execution by honoring thread
 * interruption.
 *
 * @param executionId the execution being run
 * @param sessionId the conversation session
 * @param userId the calling user
 * @param workspaceId tenant scope of the executing agent; framework adapters use it to scope state
 *     stores keyed only by session id so agents in the same project database cannot collide
 * @param requestHeaders headers of the originating request
 * @param resume whether this execution resumes a suspended one
 * @param resumeData the value the suspended execution is resumed with
 * @param previousExecutionCancelled whether the session's most recent prior execution was
 *     cancelled mid-run; adapters that persist per-step state use it to fence off that run's
 *     partial writes
 * @param metadata opaque framework-owned state that the platform forwards unchanged on resume
 */
public record RequestContext(
    String executionId,
    String sessionId,
    String userId,
    String workspaceId,
    Map<String, String> requestHeaders,
    boolean resume,
    JsonNode resumeData,
    boolean previousExecutionCancelled,
    Map<String, JsonNode> metadata) {

  /** Returns a new builder. */
  public static Builder builder() {
    return new Builder();
  }

  /** Builder for {@link RequestContext}. */
  public static final class Builder {
    private String executionId;
    private String sessionId;
    private String userId;
    private String workspaceId;
    private Map<String, String> requestHeaders;
    private boolean resume;
    private JsonNode resumeData;
    private boolean previousExecutionCancelled;
    private Map<String, JsonNode> metadata;

    private Builder() {}

    public Builder executionId(String v) { executionId = v; return this; }
    public Builder sessionId(String v) { sessionId = v; return this; }
    public Builder userId(String v) { userId = v; return this; }
    public Builder workspaceId(String v) { workspaceId = v; return this; }
    public Builder requestHeaders(Map<String, String> v) { requestHeaders = v; return this; }
    public Builder resume(boolean v) { resume = v; return this; }
    public Builder resumeData(JsonNode v) { resumeData = v; return this; }
    public Builder previousExecutionCancelled(boolean v) { previousExecutionCancelled = v; return this; }
    public Builder metadata(Map<String, JsonNode> v) { metadata = v; return this; }

    public RequestContext build() {
      return new RequestContext(
          executionId, sessionId, userId, workspaceId, requestHeaders, resume, resumeData,
          previousExecutionCancelled, metadata);
    }
  }
}
