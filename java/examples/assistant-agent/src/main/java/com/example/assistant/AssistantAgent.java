package com.example.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.mongodb.agentengine.runner.RunnableApp;
import com.mongodb.agentengine.runner.SecureLlm;
import com.mongodb.agentengine.sdk.AgentInput;
import com.mongodb.agentengine.sdk.AgentOutput;
import com.mongodb.agentengine.sdk.BaseAgent;
import com.mongodb.agentengine.sdk.LLMResponse;
import com.mongodb.agentengine.sdk.LLMToolCall;
import com.mongodb.agentengine.sdk.Message;
import com.mongodb.agentengine.sdk.MessageContent;
import com.mongodb.agentengine.sdk.RequestContext;
import com.mongodb.agentengine.sdk.Role;
import com.mongodb.agentengine.sdk.StreamEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Asks the model, runs the tools it requests, and repeats until it answers in text. */
final class AssistantAgent implements BaseAgent {

  private static final String SYSTEM_PROMPT = "You are a concise assistant. Use your tools when they help. Answer in one or two sentences.";
  private static final int MAX_ROUNDS = 5;

  private final RunnableApp app;
  private final SecureLlm llm;

  AssistantAgent(RunnableApp app, SecureLlm llm) {
    this.app = app;
    this.llm = llm;
  }

  @Override
  public AgentOutput execute(RequestContext ctx, AgentInput input) {
    List<Message> conversation = new ArrayList<>();
    conversation.add(Message.system(SYSTEM_PROMPT));
    conversation.add(Message.user(input.payload().path("message").asText("")));

    for (int round = 0; round < MAX_ROUNDS; round++) {
      LLMResponse response = llm.invoke(conversation, Map.of("temperature", 0.0));
      if (response.toolCalls() == null || response.toolCalls().isEmpty()) {
        return new AgentOutput(JsonNodeFactory.instance.textNode(response.content()));
      }
      conversation.add(new Message(Role.ASSISTANT, new MessageContent.Text(response.content()), response.toolCalls(), null, null, null, null, null, null));
      for (LLMToolCall call : response.toolCalls()) {
        conversation.add(toolMessage(call));
      }
    }
    throw new IllegalStateException("The model kept calling tools for " + MAX_ROUNDS + " rounds");
  }

  private Message toolMessage(LLMToolCall call) {
    Map<String, Object> arguments = call.args() != null && call.args().isObject() ? asMap(call.args()) : Map.of();
    JsonNode result = app.callTool(call.name(), arguments, call.id());
    String text = result.isTextual() ? result.asText() : result.toString();
    return new Message(Role.TOOL, new MessageContent.Text(text), null, call.id(), false, call.name(), null, null, null);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(JsonNode node) {
    return com.mongodb.agentengine.sdk.Json.mapper().convertValue(node, Map.class);
  }

  @Override
  public Stream<StreamEvent> stream(RequestContext ctx, AgentInput input) {
    return Stream.of(new StreamEvent(execute(ctx, input).response()));
  }
}
