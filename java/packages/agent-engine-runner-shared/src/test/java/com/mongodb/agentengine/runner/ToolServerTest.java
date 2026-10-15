package com.mongodb.agentengine.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.agentengine.runner.server.ToolServer;
import com.mongodb.agentengine.sdk.BaseAgent;
import com.mongodb.agentengine.sdk.Json;
import com.mongodb.agentengine.sdk.LLMStreamChunk;
import com.mongodb.agentengine.sdk.LLMTokenUsage;
import com.mongodb.agentengine.sdk.ToolCallChunk;
import com.mongodb.agentengine.sdk.ToolDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ToolServerTest {

  private RunnableApp app;
  private ToolServer server;
  private final StubLlm llm = new StubLlm();

  @BeforeEach
  void setUp() throws Exception {
    app = new RunnableApp("tools");
    RunnerConfig config = RunnerConfig.fromEnv(Map.of("RUNNER_MODE", "tool", "APP_PORT", "0", "APP_HOST", "127.0.0.1"));
    server = new ToolServer(app, config);
    server.start();
  }

  @AfterEach
  void tearDown() {
    server.close();
  }

  private static JsonNode json(String text) throws Exception {
    return Json.mapper().readTree(text);
  }

  private TestHttp.Response post(String path, String body) {
    return TestHttp.post(server.port(), path, body);
  }

  /** The JSON payload of each {@code data:} line of an event stream. */
  private static List<JsonNode> events(String body) throws Exception {
    List<JsonNode> events = new ArrayList<>();
    for (String line : body.split("\n")) {
      if (line.startsWith("data: ")) {
        events.add(json(line.substring(6)));
      }
    }
    return events;
  }

  private static final String LLM_REQUEST =
      "{\"execution_id\":\"e1\",\"step_number\":2,\"arguments\":{\"model\":\"gpt-x\",\"llm_id\":\"main\","
          + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"options\":{\"max_tokens\":50},\"stop\":[\"END\"],"
          + "\"tools\":[{\"name\":\"lookup\",\"description\":\"Looks up\",\"parameters\":{\"type\":\"object\"}}],\"tool_choice\":\"required\",\"stream\":true}}";

  // --- plain routes ---------------------------------------------------------------------------

  @Test
  void healthReportsToolMode() throws Exception {
    var health = json(TestHttp.get(server.port(), "/health").body());

    assertEquals("tool", health.get("mode").asText());
    assertEquals("tools", health.get("component").asText());
  }

  @Test
  void toolListingIsEmptyForAnAppWithoutTools() throws Exception {
    assertEquals(0, json(TestHttp.get(server.port(), "/tools").body()).get("count").asInt());
  }

  @Test
  void guardrailChecksAreNotImplementedYet() {
    assertEquals(501, post("/guardrails/check", "{}").status());
  }

  // --- /execute -------------------------------------------------------------------------------

  @Test
  void executeRunsTheNamedToolWithItsArguments() throws Exception {
    app.tool(ToolDefinition.builder("add", "Adds", Map.of(), args -> Map.of("sum", ((Number) args.get("a")).intValue() + ((Number) args.get("b")).intValue())).build());

    var response = post("/execute", "{\"execution_id\":\"e1\",\"tool_name\":\"add\",\"arguments\":{\"a\":2,\"b\":3},\"session_id\":\"s\"}");

    assertEquals(200, response.status());
    JsonNode body = json(response.body());
    assertEquals("success", body.get("status").asText());
    assertEquals(5, body.get("result").get("sum").asInt());
    assertTrue(body.get("oob_suspend_supported").asBoolean());
    assertFalse(body.get("pod_name").asText().isBlank());
  }

  @Test
  void aThrowingToolIsReportedAsAnErrorResult() throws Exception {
    app.tool(ToolDefinition.builder("boom", "Fails", Map.of(), args -> {
      throw new IllegalStateException("kaput");
    }).build());

    JsonNode body = json(post("/execute", "{\"execution_id\":\"e1\",\"tool_name\":\"boom\",\"arguments\":{}}").body());

    assertEquals("error", body.get("status").asText());
    assertEquals("kaput", body.get("error").asText());
  }

  @Test
  void anUnknownToolIsReportedAsAnErrorResult() throws Exception {
    JsonNode body = json(post("/execute", "{\"execution_id\":\"e1\",\"tool_name\":\"nope\",\"arguments\":{}}").body());

    assertEquals("error", body.get("status").asText());
    assertEquals("Unknown tool: nope", body.get("error").asText());
  }

  @Test
  void executeRejectsAnInvalidRequest() {
    assertEquals(422, post("/execute", "{\"arguments\":{}}").status());
  }

  // --- /invoke_llm/stream ---------------------------------------------------------------------

  @Test
  void streamsTheRegisteredProvidersChunksAsEvents() throws Exception {
    app.llm("main", "gpt-x", llm);
    llm.chunks =
        List.of(
            new LLMStreamChunk("Hel", null, null, null, null, null, null),
            new LLMStreamChunk("lo", null, null, null, null, null, null),
            new LLMStreamChunk(null, List.of(new ToolCallChunk("c1", "lookup", "{}", null, 0)), null, null, null, null, null),
            new LLMStreamChunk(null, null, new LLMTokenUsage(3, 4, null, null, null, null, null), "msg_1", null, null, null));

    var response = post("/invoke_llm/stream", LLM_REQUEST);

    assertEquals(200, response.status());
    List<JsonNode> events = events(response.body());
    assertEquals("Hel", events.get(0).get("content").asText());
    assertEquals("lo", events.get(1).get("content").asText());
    assertEquals("lookup", events.get(2).get("tool_call_chunks").get(0).get("name").asText());
    assertEquals("msg_1", events.get(3).get("id").asText());
    JsonNode done = events.get(events.size() - 1);
    assertTrue(done.get("done").asBoolean());
    assertEquals(7, done.get("usage").get("total_tokens").asInt());
    assertTrue(done.get("duration_ms").asDouble() >= 0);
  }

  @Test
  void theProviderReceivesMessagesOptionsAndPerCallTools() throws Exception {
    app.llm("main", "gpt-x", llm);

    post("/invoke_llm/stream", LLM_REQUEST);

    assertEquals("hi", ((com.mongodb.agentengine.sdk.MessageContent.Text) llm.lastMessages.get(0).content()).value());
    assertEquals(50, llm.lastKwargs.get("max_tokens"));
    assertEquals("gpt-x", llm.lastKwargs.get("model"));
    assertEquals(List.of("END"), llm.lastKwargs.get("stop"));
    assertEquals("required", llm.lastKwargs.get("tool_choice"));
    assertEquals("lookup", ((List<?>) llm.lastKwargs.get("tools")).stream().map(t -> ((com.mongodb.agentengine.sdk.LLMToolSchema) t).name()).findFirst().orElseThrow());
  }

  @Test
  void aFlatRequestIsAcceptedToo() throws Exception {
    app.llm("main", "gpt-x", llm);

    var response = post("/invoke_llm/stream", "{\"execution_id\":\"e1\",\"step_number\":1,\"model\":\"gpt-x\",\"llm_id\":\"main\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");

    assertTrue(events(response.body()).get(events(response.body()).size() - 1).get("done").asBoolean());
  }

  @Test
  void aProviderFailureEndsTheStreamWithAnErrorEvent() throws Exception {
    app.llm("main", "gpt-x", llm);
    llm.failure = new IllegalStateException("provider exploded");

    List<JsonNode> events = events(post("/invoke_llm/stream", LLM_REQUEST).body());

    assertEquals("provider exploded", events.get(events.size() - 1).get("error").asText());
    assertFalse(events.get(events.size() - 1).has("done"));
  }

  @Test
  void anUnregisteredLlmIdIsAnErrorEvent() throws Exception {
    List<JsonNode> events = events(post("/invoke_llm/stream", LLM_REQUEST).body());

    assertEquals("No LLM is registered under id 'main'", events.get(0).get("error").asText());
  }

  @Test
  void theAgentIsBuiltOnceToCollectLlmRegistrations() throws Exception {
    AtomicInteger builds = new AtomicInteger();
    Supplier<BaseAgent> builder = () -> {
      builds.incrementAndGet();
      app.llm("main", "gpt-x", llm);
      return null;
    };
    app.entrypoint(builder);

    post("/invoke_llm/stream", LLM_REQUEST);
    post("/invoke_llm/stream", LLM_REQUEST);
    post("/invoke_llm/stream", LLM_REQUEST.replace("\"main\"", "\"other\""));

    assertEquals(1, builds.get(), "registrations are collected once, even for an id that never appears");
    assertEquals("stub", events(post("/invoke_llm/stream", LLM_REQUEST).body()).get(0).get("content").asText());
  }

  // --- /invoke_llm ----------------------------------------------------------------------------

  @Test
  void invokeReturnsTheFoldedResponse() throws Exception {
    app.llm("main", "gpt-x", llm);
    llm.chunks =
        List.of(
            new LLMStreamChunk("Hello", null, null, null, null, null, null),
            new LLMStreamChunk(null, null, new LLMTokenUsage(1, 2, null, null, null, null, null), null, null, null, null));

    JsonNode body = json(post("/invoke_llm", LLM_REQUEST).body());

    assertEquals("success", body.get("status").asText());
    assertEquals("Hello", body.get("result").get("content").asText());
    assertEquals(3, body.get("usage").get("total_tokens").asInt());
    assertNull(body.get("error"));
  }

  @Test
  void invokeReportsAProviderFailureInTheBody() throws Exception {
    app.llm("main", "gpt-x", llm);
    llm.failure = new IllegalStateException("nope");

    JsonNode body = json(post("/invoke_llm", LLM_REQUEST).body());

    assertEquals("error", body.get("status").asText());
    assertEquals("nope", body.get("error").asText());
  }
}
