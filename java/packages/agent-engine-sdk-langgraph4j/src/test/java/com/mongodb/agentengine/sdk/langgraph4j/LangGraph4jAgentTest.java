package com.mongodb.agentengine.sdk.langgraph4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.agentengine.runner.ExecutionScope;
import com.mongodb.agentengine.runner.OeClient;
import com.mongodb.agentengine.runner.PolicyDeniedException;
import com.mongodb.agentengine.runner.SecureExecution;
import com.mongodb.agentengine.sdk.AgentInput;
import com.mongodb.agentengine.sdk.BaseAgent;
import com.mongodb.agentengine.sdk.Json;
import com.mongodb.agentengine.sdk.RequestContext;
import com.mongodb.agentengine.sdk.StreamEvent;
import com.mongodb.agentengine.sdk.ToolDefinition;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LangGraph4jAgentTest {

  private FakeServer oe;
  private App app;
  private ScriptedChatModel model;

  @BeforeEach
  void setUp() throws Exception {
    oe = new FakeServer();
    model = new ScriptedChatModel();
    app = new App("assistant");
    // A remote tool: the OE runs it and returns the result, so the agent runtime never executes it.
    app.tool(ToolDefinition.builder("get_current_date", "Today's date", Map.of("type", "object", "properties", Map.of()), args -> {
      throw new AssertionError("a remote tool must not run in the agent runtime");
    }).build());
  }

  @AfterEach
  void tearDown() {
    oe.close();
  }

  private BaseAgent agent() throws Exception {
    app.graph(() -> {
      try {
        return app.compile(app.agentExecutor(model).systemMessage(SystemMessage.from("be brief")).build());
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    });
    return app.buildAgent();
  }

  private static RequestContext ctx(String session, String workspace) {
    return RequestContext.builder().executionId("exec-" + session).sessionId(session).workspaceId(workspace).userId("ana").build();
  }

  private static AgentInput message(String text) {
    return new AgentInput(Json.mapper().createObjectNode().put("message", text));
  }

  /** Runs one turn the way the runtime does: inside an execution that talks to the (fake) OE. */
  private String turn(BaseAgent agent, RequestContext ctx, String text) throws Exception {
    SecureExecution execution = new SecureExecution(app, new OeClient(oe.url(), Duration.ofSeconds(5)), ctx.executionId(), Map.of());
    return ExecutionScope.call(execution, () -> agent.execute(ctx, message(text)).response().asText());
  }

  // --- a turn ---------------------------------------------------------------------------------

  @Test
  void aPlainAnswerComesBackAsTheResponse() throws Exception {
    model.answer("Hello there");

    assertEquals("Hello there", turn(agent(), ctx("s1", "w1"), "hi"));

    List<ChatMessage> sent = model.lastMessages();
    assertEquals(ChatMessageType.SYSTEM, sent.get(0).type());
    assertEquals(ChatMessageType.USER, sent.get(sent.size() - 1).type());
    assertTrue(oe.requests().isEmpty(), "no tool call means nothing to approve");
  }

  @Test
  void aToolCallGoesThroughTheOeAndItsResultReachesTheModel() throws Exception {
    model.callTool("call-1", "get_current_date", "{}").answer("It is October 15, 2026.");
    oe.reply(200, "{\"proceed\":true,\"status\":\"success\",\"result\":\"2026-10-15\"}");

    String answer = turn(agent(), ctx("s1", "w1"), "what is the date?");

    assertEquals("It is October 15, 2026.", answer);
    var approval = oe.only();
    assertEquals("/tool/execute", approval.path());
    JsonNode body = approval.json();
    assertEquals("get_current_date", body.get("tool_name").asText());
    assertEquals("call-1", body.get("tool_call_id").asText());
    assertEquals("exec-s1", body.get("execution_id").asText());
    ChatMessage lastSent = model.lastMessages().get(model.lastMessages().size() - 1);
    assertEquals("2026-10-15", ((ToolExecutionResultMessage) lastSent).text());
  }

  @Test
  void theModelIsOfferedTheRegisteredTools() throws Exception {
    model.answer("ok");

    turn(agent(), ctx("s1", "w1"), "hi");

    assertEquals(List.of("get_current_date"), model.requests.get(0).toolSpecifications().stream().map(t -> t.name()).toList());
  }

  @Test
  void aFailingToolIsReportedToTheModelAsAnError() throws Exception {
    model.callTool("call-1", "get_current_date", "{}").answer("Sorry, I could not get the date.");
    oe.reply(200, "{\"proceed\":true,\"status\":\"error\",\"error\":\"clock unavailable\"}");

    String answer = turn(agent(), ctx("s1", "w1"), "date?");

    assertEquals("Sorry, I could not get the date.", answer);
    ChatMessage toolResult = model.lastMessages().get(model.lastMessages().size() - 1);
    assertEquals("Error: clock unavailable", ((ToolExecutionResultMessage) toolResult).text());
  }

  @Test
  void aRefusedToolCallEndsTheTurn() throws Exception {
    model.callTool("call-1", "get_current_date", "{}").answer("never reached");
    oe.reply(200, "{\"proceed\":false,\"reason\":\"tool blocked by policy\"}");
    BaseAgent agent = agent();

    var error = assertThrows(Exception.class, () -> turn(agent, ctx("s1", "w1"), "date?"));

    assertTrue(causes(error).stream().anyMatch(PolicyDeniedException.class::isInstance), error.toString());
    assertEquals(1, model.requests.size(), "the model is not asked again after a refusal");
  }

  private static List<Throwable> causes(Throwable error) {
    java.util.ArrayList<Throwable> chain = new java.util.ArrayList<>();
    for (Throwable t = error; t != null && !chain.contains(t); t = t.getCause()) {
      chain.add(t);
    }
    return chain;
  }

  // --- conversation memory --------------------------------------------------------------------

  @Test
  void aSessionRemembersEarlierTurns() throws Exception {
    model.answer("Nice to meet you, Ana.").answer("Your name is Ana.");
    BaseAgent agent = agent();

    turn(agent, ctx("s1", "w1"), "My name is Ana.");
    turn(agent, ctx("s1", "w1"), "What is my name?");

    List<ChatMessage> secondTurn = model.lastMessages();
    assertTrue(secondTurn.stream().anyMatch(m -> m instanceof AiMessage ai && "Nice to meet you, Ana.".equals(ai.text())), "the first answer is in the history: " + secondTurn);
    assertEquals(3, secondTurn.stream().filter(m -> m.type() != ChatMessageType.SYSTEM).count());
  }

  @Test
  void sessionsDoNotShareConversations() throws Exception {
    model.answer("one").answer("two").answer("three");
    BaseAgent agent = agent();

    turn(agent, ctx("s1", "w1"), "first");
    turn(agent, ctx("s2", "w1"), "other session");
    turn(agent, ctx("s1", "w2"), "other workspace");

    assertEquals(2, model.requests.get(1).messages().size(), "system + the new user message only");
    assertEquals(2, model.requests.get(2).messages().size(), "the workspace scopes the conversation too");
  }

  // --- streaming and config -------------------------------------------------------------------

  @Test
  void streamEmitsOneEventPerNode() throws Exception {
    model.answer("done");
    BaseAgent agent = agent();
    SecureExecution execution = new SecureExecution(app, new OeClient(oe.url(), Duration.ofSeconds(5)), "exec-s1", Map.of());

    List<StreamEvent> events = ExecutionScope.call(execution, () -> agent.stream(ctx("s1", "w1"), message("hi")).toList());

    assertTrue(events.size() >= 2, events.toString());
    assertTrue(events.stream().allMatch(e -> "node".equals(e.event())));
    assertTrue(events.stream().anyMatch(e -> "agent".equals(e.data().get("node").asText())));
    assertEquals("done", events.stream().filter(e -> e.data().has("message")).reduce((a, b) -> b).orElseThrow().data().get("message").asText());
  }

  @Test
  void theConversationKeyScopesTheSessionToTheWorkspace() {
    assertEquals("s1:w1", LangGraph4jAgent.config(ctx("s1", "w1"), false).threadId().orElseThrow());
    assertEquals("s1", LangGraph4jAgent.config(RequestContext.builder().executionId("e").sessionId("s1").build(), false).threadId().orElseThrow());
    assertEquals("e:w1", LangGraph4jAgent.config(RequestContext.builder().executionId("e").workspaceId("w1").build(), false).threadId().orElseThrow());
  }
}
