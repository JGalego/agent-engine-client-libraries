package com.mongodb.agentengine.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.mongodb.agentengine.runner.server.AerServer;
import com.mongodb.agentengine.sdk.AgentInput;
import com.mongodb.agentengine.sdk.AgentOutput;
import com.mongodb.agentengine.sdk.BaseAgent;
import com.mongodb.agentengine.sdk.Json;
import com.mongodb.agentengine.sdk.RequestContext;
import com.mongodb.agentengine.sdk.SessionMessage;
import com.mongodb.agentengine.sdk.SessionMessagesResponse;
import com.mongodb.agentengine.sdk.SessionSummary;
import com.mongodb.agentengine.sdk.SessionsSummaryResponse;
import com.mongodb.agentengine.sdk.StreamEvent;
import com.mongodb.agentengine.sdk.SuspendException;
import com.mongodb.agentengine.sdk.ToolDefinition;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AerServerTest {

  /** Echoes the message and records what the runtime handed it. */
  private static final class RecordingAgent implements BaseAgent {
    final AtomicReference<RequestContext> context = new AtomicReference<>();
    final AtomicReference<AgentInput> input = new AtomicReference<>();
    RuntimeException failure;

    @Override
    public AgentOutput execute(RequestContext ctx, AgentInput in) {
      context.set(ctx);
      input.set(in);
      if (failure != null) {
        throw failure;
      }
      return new AgentOutput(JsonNodeFactory.instance.textNode("echo: " + in.payload().path("message").asText()));
    }

    @Override
    public Stream<StreamEvent> stream(RequestContext ctx, AgentInput in) {
      return Stream.empty();
    }
  }

  private FakeServer oe;
  private RecordingAgent agent;
  private RunnableApp app;
  private AerServer server;

  @BeforeEach
  void setUp() {
    oe = new FakeServer();
    agent = new RecordingAgent();
    app = new RunnableApp("test-agent");
    Supplier<BaseAgent> builder = () -> agent;
    app.entrypoint(builder);
  }

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.close();
    }
    oe.close();
  }

  private RunnerConfig config(String oeUrl) {
    return RunnerConfig.fromEnv(Map.of("RUNNER_MODE", "aer", "APP_PORT", "0", "APP_HOST", "127.0.0.1", "OE_URL", oeUrl, "APP_ID", "ws1", "ORG_ID", "org1", "PROJECT_ID", "proj1"));
  }

  private AerServer start(Map<String, Boolean> features) throws Exception {
    oe.reply(200, "{}");
    server = new AerServer(app, config(oe.url()), features, "langgraph4j");
    server.start();
    return server;
  }

  private static JsonNode json(String text) throws Exception {
    return Json.mapper().readTree(text);
  }

  private TestHttp.Response execute(String body) {
    return TestHttp.post(server.port(), "/execute", body);
  }

  // --- startup --------------------------------------------------------------------------------

  @Test
  void startupAdvertisesCapabilitiesToTheOe() throws Exception {
    start(Map.of("memory", true));

    var request = oe.requests().get(0);
    assertEquals("POST", request.method());
    assertEquals("/agent/capabilities", request.path());
    assertEquals(
        json("{\"workspace_id\":\"ws1\",\"org_id\":\"org1\",\"project_id\":\"proj1\",\"language\":\"java\",\"framework\":\"langgraph4j\","
            + "\"features\":{\"owner_callback_fallback\":true,\"memory\":true}}"),
        request.json());
  }

  @Test
  void aRejectedRegistrationFailsStartup() {
    oe.reply(400, "unknown language");
    server = null;

    var error = assertThrows(IllegalStateException.class, () -> {
      var rejected = new AerServer(app, config(oe.url()), Map.of(), null);
      try {
        rejected.start();
      } finally {
        rejected.close();
      }
    });

    assertTrue(error.getMessage().contains("rejected"), error.getMessage());
    assertTrue(error.getMessage().contains("400"), error.getMessage());
  }

  @Test
  void startupNeedsPlatformIdentity() throws Exception {
    RunnerConfig noWorkspace = RunnerConfig.fromEnv(Map.of("RUNNER_MODE", "aer", "APP_PORT", "0", "OE_URL", oe.url(), "ORG_ID", "o", "PROJECT_ID", "p"));
    var unregistered = new AerServer(app, noWorkspace, Map.of(), null);

    try {
      assertThrows(IllegalStateException.class, unregistered::start);
    } finally {
      unregistered.close();
    }
    assertTrue(oe.requests().isEmpty());
  }

  // --- plain routes ---------------------------------------------------------------------------

  @Test
  void healthDescribesTheComponent() throws Exception {
    app.tool(ToolDefinition.builder("lookup", "Looks up", Map.of(), a -> "x").build());
    start(Map.of());

    var response = TestHttp.get(server.port(), "/health");

    assertEquals(200, response.status());
    JsonNode health = json(response.body());
    assertEquals("healthy", health.get("status").asText());
    assertEquals("test-agent", health.get("component").asText());
    assertEquals("aer", health.get("mode").asText());
    assertEquals("lookup", health.get("details").get("tools_registered").get(0).asText());
    assertTrue(health.get("details").get("agent_builder_ready").asBoolean());
  }

  @Test
  void rootReportsTheService() throws Exception {
    start(Map.of());

    assertEquals(json("{\"service\":\"test-agent\",\"mode\":\"aer\",\"status\":\"running\"}"), json(TestHttp.get(server.port(), "/").body()));
  }

  @Test
  void toolsListsRegisteredDefinitions() throws Exception {
    app.tool(ToolDefinition.builder("lookup", "Looks up", Map.of("type", "object"), a -> "x").remote(false).scopes(List.of("read")).build());
    start(Map.of());

    JsonNode tools = json(TestHttp.get(server.port(), "/tools").body());

    assertEquals(1, tools.get("count").asInt());
    JsonNode tool = tools.get("tools").get(0);
    assertEquals("lookup", tool.get("name").asText());
    assertTrue(tool.get("is_local").asBoolean());
    assertEquals("read", tool.get("scopes").get(0).asText());
    assertEquals("object", tool.get("parameters").get("type").asText());
  }

  @Test
  void warmUpAnswersNoContent() throws Exception {
    start(Map.of());

    assertEquals(204, TestHttp.post(server.port(), "/warm-up", "").status());
  }

  // --- session queries ------------------------------------------------------------------------

  @Test
  void sessionQueriesAreNotSupportedWithoutAQuerySource() throws Exception {
    start(Map.of());

    assertEquals(501, TestHttp.get(server.port(), "/query/sessions?session_ids=s1").status());
    assertEquals(501, TestHttp.get(server.port(), "/query/sessions/s1/messages").status());
  }

  @Test
  void anEmptySessionListNeedsNoQuerySource() throws Exception {
    start(Map.of());

    assertEquals(json("{\"sessions\":[]}"), json(TestHttp.get(server.port(), "/query/sessions").body()));
  }

  @Test
  void summariesAreReadForTheRequestedSessionsInTheConfiguredWorkspace() throws Exception {
    AtomicReference<List<String>> asked = new AtomicReference<>();
    AtomicReference<String> workspace = new AtomicReference<>();
    app.sessionQuery(new SessionQuery() {
      @Override
      public SessionsSummaryResponse summaries(List<String> sessionIds, String workspaceId) {
        asked.set(sessionIds);
        workspace.set(workspaceId);
        return new SessionsSummaryResponse(List.of(new SessionSummary("s1", "", "", 2, "hello")));
      }

      @Override
      public SessionMessagesResponse messages(String sessionId, String workspaceId) {
        throw new AssertionError();
      }
    });
    start(Map.of());

    var response = TestHttp.get(server.port(), "/query/sessions?session_ids=s1&session_ids=s%202");

    assertEquals(200, response.status());
    assertEquals(List.of("s1", "s 2"), asked.get());
    assertEquals("ws1", workspace.get());
    assertEquals(json("{\"sessions\":[{\"session_id\":\"s1\",\"last_activity\":\"\",\"created_at\":\"\",\"message_count\":2,\"first_message_preview\":\"hello\"}]}"), json(response.body()));
  }

  @Test
  void tooManySessionsInOneQueryAreRejected() throws Exception {
    app.sessionQuery(new SessionQuery() {
      @Override
      public SessionsSummaryResponse summaries(List<String> sessionIds, String workspaceId) {
        throw new AssertionError();
      }

      @Override
      public SessionMessagesResponse messages(String sessionId, String workspaceId) {
        throw new AssertionError();
      }
    });
    start(Map.of());
    String ids = String.join("&", java.util.Collections.nCopies(201, "session_ids=s"));

    assertEquals(422, TestHttp.get(server.port(), "/query/sessions?" + ids).status());
  }

  @Test
  void messagesAreReadForTheSessionNamedInThePath() throws Exception {
    AtomicReference<String> asked = new AtomicReference<>();
    app.sessionQuery(new SessionQuery() {
      @Override
      public SessionsSummaryResponse summaries(List<String> sessionIds, String workspaceId) {
        throw new AssertionError();
      }

      @Override
      public SessionMessagesResponse messages(String sessionId, String workspaceId) {
        asked.set(sessionId);
        return new SessionMessagesResponse(List.of(new SessionMessage("m1", "user", "hi", "", sessionId, null, null, null, null)));
      }
    });
    start(Map.of());

    var response = TestHttp.get(server.port(), "/query/sessions/a%2Fb/messages");

    assertEquals(200, response.status());
    assertEquals("a/b", asked.get());
    assertEquals("hi", json(response.body()).get("messages").get(0).get("content").asText());
  }

  @Test
  void unknownPathsAndMethodsAreRejected() throws Exception {
    start(Map.of());

    assertEquals(404, TestHttp.get(server.port(), "/nope").status());
    assertEquals(405, TestHttp.get(server.port(), "/execute").status());
    assertEquals("Not Found", json(TestHttp.get(server.port(), "/nope").body()).get("detail").asText());
  }

  // --- execute --------------------------------------------------------------------------------

  @Test
  void executeRunsTheAgentAndReportsCompletion() throws Exception {
    start(Map.of());
    oe.reply(200, "{}");

    var response = execute("{\"execution_id\":\"e1\",\"message\":\"hi\",\"platform_api_url\":\"http://ignored\",\"user_id\":\"ana\",\"session_id\":\"s1\",\"workspace_id\":\"ws1\"}");

    assertEquals(200, response.status());
    assertEquals(json("{\"status\":\"completed\",\"result\":\"echo: hi\"}"), json(response.body()));
    var callback = oe.requests().get(1);
    assertEquals("/executor/callback", callback.path());
    assertEquals(json("{\"execution_id\":\"e1\",\"status\":\"COMPLETED\",\"result\":\"echo: hi\"}"), callback.json());
  }

  @Test
  void aSuspendedAgentIsReportedAsSuspendedNotFailed() throws Exception {
    agent.failure = new SuspendException("awaiting_human_review", Map.of("claim", "C-1"), Map.of("step", 2));
    start(Map.of());
    oe.reply(200, "{}");

    var response = execute("{\"execution_id\":\"e1\",\"message\":\"hi\",\"platform_api_url\":\"x\",\"suspend_generation\":4}");

    assertEquals(200, response.status());
    assertEquals(json("{\"status\":\"suspended\",\"suspend_reason\":\"awaiting_human_review\"}"), json(response.body()));
    assertEquals(
        json("{\"execution_id\":\"e1\",\"status\":\"SUSPENDED\",\"suspend_generation\":4,\"suspend_reason\":\"awaiting_human_review\","
            + "\"suspend_context\":{\"claim\":\"C-1\"},\"metadata\":{\"step\":2}}"),
        oe.requests().get(1).json());
  }

  @Test
  void theAgentSeesTheExecutionContext() throws Exception {
    start(Map.of());
    oe.reply(200, "{}");

    execute("{\"execution_id\":\"e1\",\"message\":\"hi\",\"platform_api_url\":\"x\",\"user_id\":\"ana\",\"workspace_id\":\"w\",\"resume\":true,"
        + "\"resume_data\":{\"ok\":true},\"previous_execution_cancelled\":true,\"custom_headers\":{\"x-tenant\":\"t1\"},\"metadata\":{\"k\":\"v\"}}");

    RequestContext ctx = agent.context.get();
    assertEquals("e1", ctx.executionId());
    assertEquals("e1", ctx.sessionId(), "the session defaults to the execution id");
    assertEquals("ana", ctx.userId());
    assertEquals("w", ctx.workspaceId());
    assertTrue(ctx.resume());
    assertTrue(ctx.previousExecutionCancelled());
    assertEquals(json("{\"ok\":true}"), ctx.resumeData());
    assertEquals(Map.of("x-tenant", "t1"), ctx.requestHeaders());
    assertEquals("v", ctx.metadata().get("k").asText());
  }

  @Test
  void payloadFieldsReachTheAgentAlongsideTheResolvedMessage() throws Exception {
    start(Map.of());
    oe.reply(200, "{}");

    execute("{\"execution_id\":\"e1\",\"platform_api_url\":\"x\",\"payload\":{\"message\":\"from payload\",\"extra\":42}}");

    assertEquals(json("{\"message\":\"from payload\",\"extra\":42}"), agent.input.get().payload());
  }

  @Test
  void aMessageInBothPlacesIsAmbiguous() throws Exception {
    start(Map.of());

    var response = execute("{\"execution_id\":\"e1\",\"message\":\"a\",\"platform_api_url\":\"x\",\"payload\":{\"message\":\"b\"}}");

    assertEquals(400, response.status());
    assertTrue(json(response.body()).get("detail").asText().startsWith("Ambiguous request"));
    assertEquals(1, oe.requests().size(), "no callback for a rejected request");
  }

  @Test
  void anEmptyPayloadMessageDoesNotConflict() throws Exception {
    start(Map.of());
    oe.reply(200, "{}");

    var response = execute("{\"execution_id\":\"e1\",\"message\":\"a\",\"platform_api_url\":\"x\",\"payload\":{\"message\":\"\"}}");

    assertEquals(200, response.status());
    assertEquals("echo: a", json(response.body()).get("result").asText());
  }

  @Test
  void callbacksGoToTheConfiguredOeNotTheRequestedUrl() throws Exception {
    start(Map.of());
    oe.reply(200, "{}");

    execute("{\"execution_id\":\"e1\",\"message\":\"hi\",\"platform_api_url\":\"http://attacker.invalid\"}");

    assertEquals("/executor/callback", oe.requests().get(1).path());
  }

  @Test
  void aSuspendGenerationIsEchoedOnTheCallback() throws Exception {
    start(Map.of());
    oe.reply(200, "{}");

    execute("{\"execution_id\":\"e1\",\"message\":\"hi\",\"platform_api_url\":\"x\",\"suspend_generation\":3}");

    assertEquals(3, oe.requests().get(1).json().get("suspend_generation").asInt());
  }

  @Test
  void aFailingAgentIsReportedAsAnErrorCallback() throws Exception {
    start(Map.of());
    oe.reply(200, "{}");
    agent.failure = new IllegalStateException("model unavailable");

    var response = execute("{\"execution_id\":\"e1\",\"message\":\"hi\",\"platform_api_url\":\"x\"}");

    assertEquals(500, response.status());
    assertTrue(json(response.body()).get("detail").asText().contains("model unavailable"));
    JsonNode callback = oe.requests().get(1).json();
    assertEquals("ERROR", callback.get("status").asText());
    assertEquals("model unavailable", callback.get("error").asText());
    assertFalse(callback.has("result"));
  }

  @Test
  void aStructuredResultIsReturnedAsJsonText() throws Exception {
    start(Map.of());
    oe.reply(200, "{}");
    app.entrypoint((Supplier<BaseAgent>) () -> new BaseAgent() {
      @Override
      public AgentOutput execute(RequestContext ctx, AgentInput in) {
        return new AgentOutput(JsonNodeFactory.instance.objectNode().put("answer", 42));
      }

      @Override
      public Stream<StreamEvent> stream(RequestContext ctx, AgentInput in) {
        return Stream.empty();
      }
    });

    var response = execute("{\"execution_id\":\"e1\",\"message\":\"hi\",\"platform_api_url\":\"x\"}");

    assertEquals("{\"answer\":42}", json(response.body()).get("result").asText());
    assertEquals(42, oe.requests().get(1).json().get("result").get("answer").asInt());
  }

  @Test
  void invalidRequestsAreRejectedBeforeAnythingRuns() throws Exception {
    start(Map.of());

    assertEquals(422, execute("{\"message\":\"no ids\"}").status());
    assertEquals(422, execute("not json").status());
    assertEquals(1, oe.requests().size());
  }
}
