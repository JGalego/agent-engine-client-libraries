package com.mongodb.agentengine.runner.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mongodb.agentengine.runner.ExecutionScope;
import com.mongodb.agentengine.runner.NodeExecutionLogger;
import com.mongodb.agentengine.runner.OeClient;
import com.mongodb.agentengine.runner.RunnableApp;
import com.mongodb.agentengine.runner.RunnerConfig;
import com.mongodb.agentengine.runner.RunnerMode;
import com.mongodb.agentengine.runner.SecureExecution;
import com.mongodb.agentengine.runner.SessionQuery;
import com.mongodb.agentengine.runner.StreamChunks;
import com.mongodb.agentengine.runner.models.AerExecuteResponse;
import com.mongodb.agentengine.runner.models.ExecuteRequest;
import com.mongodb.agentengine.runner.models.ExecutorCallback;
import com.mongodb.agentengine.sdk.AgentInput;
import com.mongodb.agentengine.sdk.AgentOutput;
import com.mongodb.agentengine.sdk.BaseAgent;
import com.mongodb.agentengine.sdk.RequestContext;
import com.mongodb.agentengine.sdk.SessionMessagesResponse;
import com.mongodb.agentengine.sdk.SessionsSummaryResponse;
import com.mongodb.agentengine.sdk.SuspendException;
import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The agent runtime (AER): runs agent turns the orchestration engine dispatches and reports the
 * outcome back.
 *
 * <p>On startup it advertises the agent's capabilities to the OE and refuses to serve if the OE does
 * not accept them. For each {@code POST /execute} it builds the agent, runs the turn, delivers an
 * {@code /executor/callback} to the OE, and answers the dispatch.
 */
public final class AerServer extends ComponentServer {

  private static final System.Logger LOG = System.getLogger(AerServer.class.getName());
  private static final Duration OE_TIMEOUT = Duration.ofSeconds(30);
  private static final int CALLBACK_ATTEMPTS = 3;
  private static final int REGISTRATION_ATTEMPTS = 5;
  // The platform's session list pages at 50; this leaves room for batched enrichment.
  private static final int MAX_QUERIED_SESSIONS = 200;

  private final RunnerConfig config;
  private final Map<String, Boolean> features;
  private final String framework;
  private final OeClient oe;

  public AerServer(RunnableApp app, RunnerConfig config, Map<String, Boolean> features, String framework) throws IOException {
    super(app, RunnerMode.AER, config.host(), config.portFor(RunnerMode.AER));
    this.config = config;
    this.features = features;
    this.framework = framework == null ? "" : framework;
    this.oe = OeClient.fromEnvironment(config.oeUrl(), OE_TIMEOUT, System.getenv());
    http().route("POST", "/execute", request -> JsonHttpServer.Reply.ok(execute(request.json(ExecuteRequest.class))));
    http().route("POST", "/warm-up", request -> JsonHttpServer.Reply.noContent());
    http().route("GET", "/query/sessions", request -> JsonHttpServer.Reply.ok(querySummaries(request.queryValues("session_ids"))));
    http().route("GET", "/query/sessions/{session_id}/messages", request -> JsonHttpServer.Reply.ok(queryMessages(request.pathParams().get("session_id"))));
  }

  @Override
  protected Map<String, Object> healthDetails() {
    Map<String, Object> details = new LinkedHashMap<>(super.healthDetails());
    details.put("agent_builder_ready", true);
    return details;
  }

  /** Tells the OE which language, framework and features this runtime has. */
  @Override
  protected void onStartup() {
    if (config.oeUrl().isEmpty() || config.workspaceId().isEmpty()) {
      throw new IllegalStateException("Capability registration requires OE_URL and APP_ID");
    }
    if (config.orgId().isEmpty() || config.projectId().isEmpty()) {
      throw new IllegalStateException("Capability registration requires ORG_ID and PROJECT_ID for workspace " + config.workspaceId());
    }
    Map<String, Object> declared = new LinkedHashMap<>();
    // This runtime always falls back to the OE URL it was configured with, so the OE may send owner
    // callback URLs it can replace.
    declared.put("owner_callback_fallback", true);
    declared.putAll(features);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("workspace_id", config.workspaceId());
    payload.put("org_id", config.orgId());
    payload.put("project_id", config.projectId());
    payload.put("language", "java");
    payload.put("framework", framework);
    payload.put("features", declared);

    OeClient.Response response = oe.postJson("/agent/capabilities", payload, REGISTRATION_ATTEMPTS);
    if (!response.ok()) {
      throw new IllegalStateException("Capability registration was rejected for " + config.workspaceId() + " with status " + response.status() + ": " + response.body());
    }
    LOG.log(System.Logger.Level.INFO, "Agent capabilities advertised for workspace {0} (language=java framework={1})", config.workspaceId(), framework.isEmpty() ? "-" : framework);
  }

  // ---------------------------------------------------------------------------
  // Execution
  // ---------------------------------------------------------------------------

  AerExecuteResponse execute(ExecuteRequest request) {
    String message = resolveMessage(request);
    ObjectNode payload = request.payload() != null && request.payload().isObject() ? ((ObjectNode) request.payload()).deepCopy() : JsonNodeFactory.instance.objectNode();
    payload.put("message", message);

    RequestContext context =
        RequestContext.builder()
            .executionId(request.executionId())
            .sessionId(request.effectiveSessionId())
            .userId(request.userId())
            .workspaceId(request.workspaceId())
            .requestHeaders(request.customHeaders())
            .resume(request.resume())
            .resumeData(request.resumeData())
            .previousExecutionCancelled(request.previousExecutionCancelled())
            .metadata(request.metadata())
            .build();

    // The callback goes to the OE this runtime was configured with, never to a URL the request names:
    // a caller-chosen URL would let a request forge tool results and approval decisions.
    try (StreamChunks chunks = new StreamChunks(oe, request.executionId());
        NodeExecutionLogger nodes = new NodeExecutionLogger(oe, request.executionId(), context.sessionId(), request.userId(), request.orgId(), request.projectId())) {
      SecureExecution secure = new SecureExecution(app(), oe, request.executionId(), request.customHeaders()).onText(chunks::text).onNodes(nodes);
      AgentOutput output = ExecutionScope.call(secure, () -> {
        BaseAgent agent = app().buildAgent();
        return agent.execute(context, new AgentInput(payload));
      });
      // Deliver text and node events before the callback that settles the execution.
      chunks.close();
      nodes.close();
      JsonNode result = output.response();
      deliver(ExecutorCallback.completed(request.executionId(), request.suspendGeneration(), result));
      return AerExecuteResponse.completed(result.isTextual() ? result.asText() : result.toString());
    } catch (SuspendException e) {
      LOG.log(System.Logger.Level.INFO, "Execution {0} suspended: {1}", request.executionId(), e.reason());
      deliver(ExecutorCallback.suspended(request.executionId(), request.suspendGeneration(), e.reason(), e.context(), e.metadata()));
      return AerExecuteResponse.suspended(e.reason());
    } catch (Exception e) {
      LOG.log(System.Logger.Level.ERROR, "Execution " + request.executionId() + " failed", e);
      deliver(ExecutorCallback.failed(request.executionId(), request.suspendGeneration(), String.valueOf(e.getMessage())));
      throw new HttpError(500, "Execution failed: " + e.getMessage());
    }
  }

  // ---------------------------------------------------------------------------
  // Session queries
  // ---------------------------------------------------------------------------

  private SessionsSummaryResponse querySummaries(List<String> sessionIds) {
    if (sessionIds.size() > MAX_QUERIED_SESSIONS) {
      throw new HttpError(422, "At most " + MAX_QUERIED_SESSIONS + " session_ids may be queried at once");
    }
    if (sessionIds.isEmpty()) {
      return new SessionsSummaryResponse(List.of());
    }
    return sessionQuery().summaries(sessionIds, config.workspaceId());
  }

  private SessionMessagesResponse queryMessages(String sessionId) {
    return sessionQuery().messages(sessionId, config.workspaceId());
  }

  private SessionQuery sessionQuery() {
    return app().sessionQuery().orElseThrow(() -> new HttpError(501, "session queries are not supported by this framework"));
  }

  /**
   * The user message of a request: {@code payload.message} when the top-level {@code message} is
   * empty. Sending both is ambiguous and rejected.
   */
  static String resolveMessage(ExecuteRequest request) {
    String fromPayload = request.payloadMessage();
    boolean hasPayloadMessage = fromPayload != null && !fromPayload.isEmpty();
    if (!request.message().isEmpty() && hasPayloadMessage) {
      throw new HttpError(400, "Ambiguous request: 'message' was provided both at the top level and inside 'payload'. Send the message in exactly one place.");
    }
    return hasPayloadMessage ? fromPayload : request.message();
  }

  private void deliver(ExecutorCallback callback) {
    OeClient.Response response = oe.postJson("/executor/callback", callback, CALLBACK_ATTEMPTS);
    if (!response.ok()) {
      LOG.log(System.Logger.Level.ERROR, "OE rejected the {0} callback for execution {1}: HTTP {2} {3}", callback.status(), callback.executionId(), response.status(), response.body());
    }
  }
}
