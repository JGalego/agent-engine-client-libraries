package com.mongodb.agentengine.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.agentengine.sdk.Json;
import com.mongodb.agentengine.sdk.LLMResponse;
import com.mongodb.agentengine.sdk.LLMToolSchema;
import com.mongodb.agentengine.sdk.Message;
import com.mongodb.agentengine.sdk.SuspendException;
import com.mongodb.agentengine.sdk.ToolDefinition;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SecureExecutionTest {

  private FakeServer oe;
  private RunnableApp app;
  private SecureExecution execution;
  private final AtomicInteger localCalls = new AtomicInteger();

  @BeforeEach
  void setUp() {
    oe = new FakeServer();
    app = new RunnableApp("agent");
    app.tool(ToolDefinition.builder("local_tool", "Runs here", Map.of(), args -> {
      localCalls.incrementAndGet();
      return Map.of("echo", args.get("x"));
    }).remote(false).redactFields(List.of("secret")).build());
    app.tool(ToolDefinition.builder("failing_tool", "Fails", Map.of(), args -> {
      throw new IllegalStateException("disk full");
    }).remote(false).build());
    app.tool(ToolDefinition.builder("suspending_tool", "Asks a human", Map.of(), args -> {
      throw new SuspendException("awaiting_human_review", Map.of("claim", "C-1"));
    }).remote(false).build());
    app.tool(ToolDefinition.builder("remote_tool", "Runs in the tool server", Map.of(), args -> {
      throw new AssertionError("a remote tool must never run in the agent runtime");
    }).build());
    execution = new SecureExecution(app, new OeClient(oe.url(), Duration.ofSeconds(5)), "exec-1", Map.of("x-tenant", "t1"));
  }

  @AfterEach
  void tearDown() {
    oe.close();
  }

  private static JsonNode json(String text) throws Exception {
    return Json.mapper().readTree(text);
  }

  // --- tools ----------------------------------------------------------------------------------

  @Test
  void aLocalToolRunsHereAndIsReportedToTheOe() throws Exception {
    oe.reply(200, "{\"proceed\":true,\"route_to\":\"callback\"}").reply(200, "{}");

    JsonNode result = execution.callTool("local_tool", Map.of("x", 7), "call-1");

    assertEquals(json("{\"echo\":7}"), result);
    var approval = oe.requests().get(0);
    assertEquals("/tool/execute", approval.path());
    JsonNode body = approval.json();
    assertEquals("exec-1", body.get("execution_id").asText());
    assertEquals("local_tool", body.get("tool_name").asText());
    assertEquals(7, body.get("arguments").get("x").asInt());
    assertEquals(1, body.get("step_number").asInt());
    assertEquals("call-1", body.get("tool_call_id").asText());
    assertTrue(body.get("is_local").asBoolean());
    assertEquals("secret", body.get("redact_fields").get(0).asText());
    assertEquals("t1", body.get("custom_headers").get("x-tenant").asText());

    var report = oe.requests().get(1);
    assertEquals("/tool/result", report.path());
    JsonNode reported = report.json();
    assertEquals("success", reported.get("status").asText());
    assertEquals(1, reported.get("step_number").asInt());
    assertEquals("call-1", reported.get("tool_call_id").asText());
    assertEquals(7, reported.get("result").get("echo").asInt());
    assertTrue(reported.get("duration_ms").asDouble() >= 0);
  }

  @Test
  void aFailingLocalToolIsReportedAsAnError() throws Exception {
    oe.reply(200, "{\"proceed\":true,\"route_to\":\"callback\"}").reply(200, "{}");

    var error = assertThrows(ToolExecutionException.class, () -> execution.callTool("failing_tool", Map.of(), null));

    assertEquals("disk full", error.getMessage());
    JsonNode reported = oe.requests().get(1).json();
    assertEquals("error", reported.get("status").asText());
    assertEquals("disk full", reported.get("error").asText());
  }

  @Test
  void aRemoteToolIsRunByTheOeAndItsResultReturned() throws Exception {
    oe.reply(200, "{\"proceed\":true,\"status\":\"success\",\"result\":{\"v\":1},\"duration_ms\":3.5}");

    JsonNode result = execution.callTool("remote_tool", Map.of(), null);

    assertEquals(json("{\"v\":1}"), result);
    assertEquals(1, oe.requests().size(), "nothing is reported for a call the OE ran itself");
    assertEquals(false, oe.requests().get(0).json().get("is_local").asBoolean());
  }

  @Test
  void aRemoteToolFailureIsRaised() {
    oe.reply(200, "{\"proceed\":true,\"status\":\"error\",\"error\":\"upstream 500\"}");

    var error = assertThrows(ToolExecutionException.class, () -> execution.callTool("remote_tool", Map.of(), null));

    assertEquals("upstream 500", error.getMessage());
  }

  @Test
  void anInterruptedToolReturnsAMarker() throws Exception {
    oe.reply(200, "{\"proceed\":true,\"status\":\"interrupted\"}");

    assertEquals(json("{\"interrupted\":true}"), execution.callTool("remote_tool", Map.of(), null));
  }

  @Test
  void aSuspendingRemoteToolSuspendsTheExecution() {
    String marker = "{\"suspend_reason\":\"awaiting_human_review\",\"suspend_context\":{\"claim\":\"C-1\"},\"__suspend__\":true}";
    oe.reply(200, Json.mapper().createObjectNode().put("proceed", true).put("status", "suspend").put("result", marker).toString());

    var suspension = assertThrows(SuspendException.class, () -> execution.callTool("remote_tool", Map.of(), null));

    assertEquals("awaiting_human_review", suspension.reason());
    assertEquals(Map.of("claim", "C-1"), suspension.context());
  }

  @Test
  void aSuspendingLocalToolIsReportedToTheOeAndSuspendsTheExecution() throws Exception {
    oe.reply(200, "{\"proceed\":true,\"route_to\":\"callback\"}").reply(200, "{}");

    var suspension = assertThrows(SuspendException.class, () -> execution.callTool("suspending_tool", Map.of(), "call-1"));

    assertEquals("awaiting_human_review", suspension.reason());
    JsonNode reported = oe.requests().get(1).json();
    assertEquals("/tool/result", oe.requests().get(1).path());
    assertEquals("suspend", reported.get("status").asText());
    assertEquals("awaiting_human_review", json(reported.get("result").asText()).get("suspend_reason").asText());
  }

  @Test
  void aSuspensionWithoutAReasonIsAToolFailure() {
    oe.reply(200, "{\"proceed\":true,\"status\":\"suspend\",\"result\":\"{}\"}");

    assertThrows(ToolExecutionException.class, () -> execution.callTool("remote_tool", Map.of(), null));
  }

  @Test
  void aRefusedCallSurfacesThePolicyReason() {
    oe.reply(200, "{\"proceed\":false,\"reason\":\"blocked by policy\"}");

    var error = assertThrows(PolicyDeniedException.class, () -> execution.callTool("local_tool", Map.of("x", 1), null));

    assertEquals("blocked by policy", error.getMessage());
    assertEquals(0, localCalls.get(), "a refused tool must not run");
  }

  @Test
  void anUnreachableOeBlocksTheCall() {
    oe.reply(500, "boom").reply(500, "boom").reply(500, "boom");

    assertThrows(PolicyDeniedException.class, () -> execution.callTool("local_tool", Map.of("x", 1), null));
    assertEquals(0, localCalls.get());
  }

  @Test
  void aLocalRouteForARemoteToolIsRejected() {
    oe.reply(200, "{\"proceed\":true,\"route_to\":\"callback\"}");

    assertThrows(ToolExecutionException.class, () -> execution.callTool("remote_tool", Map.of(), null));
  }

  @Test
  void anUnknownRouteIsRejected() {
    oe.reply(200, "{\"proceed\":true,\"route_to\":\"http://elsewhere\"}");

    assertThrows(ToolExecutionException.class, () -> execution.callTool("remote_tool", Map.of(), null));
  }

  @Test
  void unknownToolsFailBeforeAnyRequest() {
    assertThrows(IllegalArgumentException.class, () -> execution.callTool("nope", Map.of(), null));
    assertTrue(oe.requests().isEmpty());
  }

  @Test
  void stepsAreNumberedAcrossCallsAndSkipPastThoseTheOeHasUsed() throws Exception {
    oe.reply(200, "{\"proceed\":true,\"status\":\"success\",\"result\":1,\"latest_step_number\":5}")
        .reply(200, "{\"proceed\":true,\"status\":\"success\",\"result\":2}");

    execution.callTool("remote_tool", Map.of(), null);
    execution.callTool("remote_tool", Map.of(), null);

    assertEquals(1, oe.requests().get(0).json().get("step_number").asInt());
    assertEquals(6, oe.requests().get(1).json().get("step_number").asInt());
  }

  // --- LLM ------------------------------------------------------------------------------------

  private static final String STREAM =
      "data: {\"content\":\"Hel\"}\n\n"
          + "data: {\"content\":\"lo\"}\n\n"
          + "data: {\"tool_call_chunks\":[{\"id\":\"c1\",\"name\":\"lookup\",\"args\":\"{\\\"q\\\":\",\"index\":0}]}\n\n"
          + "data: {\"tool_call_chunks\":[{\"args\":\"\\\"x\\\"}\",\"index\":0}]}\n\n"
          + "data: {\"done\":true,\"pod_name\":\"p\",\"duration_ms\":5,\"usage\":{\"input_tokens\":3,\"output_tokens\":4}}\n\n";

  private void routeToRelay(String body) {
    oe.reply(200, "{\"proceed\":true,\"route_to\":\"" + oe.url() + "/tool/stream/exec-1/1\"}").reply(200, body);
  }

  private static final List<Message> HELLO = List.of(Message.user("hi"));

  @Test
  void anLlmCallIsApprovedThenStreamedThroughTheOeRelay() throws Exception {
    routeToRelay(STREAM);
    JsonNode tools = json("{\"type\":\"object\",\"properties\":{}}");

    LLMResponse response =
        execution.invokeLlm("main", "gpt-x", HELLO, List.of(new LLMToolSchema("lookup", "Looks up", tools, null, null, null)), null, Map.of("max_tokens", 50, "stop", List.of("END")));

    assertEquals("Hello", response.content());
    assertEquals("lookup", response.toolCalls().get(0).name());
    assertEquals(json("{\"q\":\"x\"}"), response.toolCalls().get(0).args());
    assertEquals(7, response.usage().totalTokens());

    var approval = oe.requests().get(0);
    assertEquals("/tool/execute", approval.path());
    JsonNode body = approval.json();
    assertEquals("invoke_llm", body.get("tool_name").asText());
    JsonNode arguments = body.get("arguments");
    assertEquals("gpt-x", arguments.get("model").asText());
    assertEquals("main", arguments.get("llm_id").asText());
    assertTrue(arguments.get("stream").asBoolean());
    assertEquals("END", arguments.get("stop").get(0).asText());
    assertEquals(50, arguments.get("options").get("max_tokens").asInt());
    assertEquals("lookup", arguments.get("tools").get(0).get("name").asText());
    assertEquals("hi", arguments.get("messages").get(0).get("content").asText());
    assertEquals("/tool/stream/exec-1/1", oe.requests().get(1).path());
  }

  @Test
  void streamedTextIsHandedToTheTextSinkAsItArrives() {
    routeToRelay(STREAM);
    List<String> seen = new java.util.ArrayList<>();
    execution.onText(seen::add);

    execution.invokeLlm("main", "m", HELLO, List.of(), null, Map.of());

    assertEquals(List.of("Hel", "lo"), seen);
  }

  @Test
  void streamedChunksArriveInOrder() {
    routeToRelay(STREAM);

    var chunks = execution.streamLlm("main", "m", HELLO, List.of(), null, Map.of()).toList();

    assertEquals("Hel", chunks.get(0).content());
    assertEquals("lo", chunks.get(1).content());
    assertEquals(7, chunks.get(chunks.size() - 1).usage().totalTokens());
  }

  @Test
  void anLlmAnswerWithoutAStreamIsUsedDirectly() {
    oe.reply(200, "{\"proceed\":true,\"status\":\"success\",\"result\":{\"content\":\"direct\",\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}}");

    LLMResponse response = execution.invokeLlm("main", "m", HELLO, List.of(), null, Map.of());

    assertEquals("direct", response.content());
    assertEquals(2, response.usage().totalTokens());
    assertEquals(1, oe.requests().size());
  }

  @Test
  void aGuardrailBlockAnswersWithItsSubstituteText() {
    oe.reply(200, "{\"proceed\":false,\"result\":\"I can't help with that.\",\"reason\":\"guardrail\"}");

    assertEquals("I can't help with that.", execution.invokeLlm("main", "m", HELLO, List.of(), null, Map.of()).content());
  }

  @Test
  void aRefusedLlmCallRaises() {
    oe.reply(200, "{\"proceed\":false,\"reason\":\"budget exceeded\"}");

    assertEquals("budget exceeded", assertThrows(PolicyDeniedException.class, () -> execution.invokeLlm("main", "m", HELLO, List.of(), null, Map.of())).getMessage());
  }

  @Test
  void anLlmErrorStatusCarriesTheProviderCode() {
    oe.reply(200, "{\"proceed\":true,\"status\":\"error\",\"error\":\"bad key\",\"error_code\":\"llm_credential_rejected\"}");

    var error = assertThrows(LlmInvocationException.class, () -> execution.invokeLlm("main", "m", HELLO, List.of(), null, Map.of()));

    assertEquals("bad key", error.getMessage());
    assertEquals("llm_credential_rejected", error.errorCode());
  }

  @Test
  void anInterruptedLlmCallYieldsACleanMarker() {
    oe.reply(200, "{\"proceed\":true,\"status\":\"interrupted\"}");

    LLMResponse response = execution.invokeLlm("main", "m", HELLO, List.of(), null, Map.of());

    assertEquals(true, response.responseMetadata().get("interrupted").asBoolean());
  }

  @Test
  void anInterruptEventEndsTheStreamCleanly() {
    routeToRelay("data: {\"content\":\"partial\"}\n\ndata: {\"interrupted\":true}\n\n");

    LLMResponse response = execution.invokeLlm("main", "m", HELLO, List.of(), null, Map.of());

    assertEquals("partial", response.content());
    assertEquals(true, response.responseMetadata().get("interrupted").asBoolean());
  }

  @Test
  void aStreamErrorEventRaises() {
    routeToRelay("data: {\"content\":\"x\"}\n\ndata: {\"error\":\"rate limited\",\"error_code\":\"rate_limit\"}\n\n");

    var error = assertThrows(LlmInvocationException.class, () -> execution.invokeLlm("main", "m", HELLO, List.of(), null, Map.of()));

    assertEquals("rate limited", error.getMessage());
    assertEquals("rate_limit", error.errorCode());
  }

  @Test
  void aStreamThatEndsWithoutDoneIsTruncated() {
    routeToRelay("data: {\"content\":\"cut off\"}\n\n");

    var error = assertThrows(LlmInvocationException.class, () -> execution.invokeLlm("main", "m", HELLO, List.of(), null, Map.of()));

    assertTrue(error.getMessage().contains("done signal"), error.getMessage());
  }

  @Test
  void malformedEventsAreSkipped() {
    routeToRelay("data: not json\n\ndata: {\"content\":\"ok\"}\n\ndata: {\"done\":true}\n\n");

    assertEquals("ok", execution.invokeLlm("main", "m", HELLO, List.of(), null, Map.of()).content());
  }

  @Test
  void aStreamUrlOutsideTheOeIsRefused() {
    oe.reply(200, "{\"proceed\":true,\"route_to\":\"http://attacker.invalid/tool/stream/x\"}");

    assertThrows(IllegalArgumentException.class, () -> execution.invokeLlm("main", "m", HELLO, List.of(), null, Map.of()));
  }

  @Test
  void aFailedRelayRaises() {
    oe.reply(200, "{\"proceed\":true,\"route_to\":\"" + oe.url() + "/tool/stream/exec-1/1\"}").reply(502, "bad gateway");

    var error = assertThrows(LlmInvocationException.class, () -> execution.invokeLlm("main", "m", HELLO, List.of(), null, Map.of()));

    assertTrue(error.getMessage().contains("502"), error.getMessage());
  }

  @Test
  void llmAndToolCallsShareOneStepSequence() throws Exception {
    routeToRelay(STREAM);
    oe.reply(200, "{\"proceed\":true,\"status\":\"success\",\"result\":1}");

    execution.invokeLlm("main", "m", HELLO, List.of(), null, Map.of());
    execution.callTool("remote_tool", Map.of(), null);

    assertEquals(1, oe.requests().get(0).json().get("step_number").asInt());
    assertEquals(2, oe.requests().get(2).json().get("step_number").asInt());
    assertNull(oe.requests().get(0).json().get("tool_call_id"));
  }
}
