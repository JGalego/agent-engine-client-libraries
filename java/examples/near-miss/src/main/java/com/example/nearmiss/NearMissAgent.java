package com.example.nearmiss;

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

/** Asks the model, runs the retrieval tools it requests, and repeats until it answers in text. */
final class NearMissAgent implements BaseAgent {

  static final String SYSTEM_PROMPT = """
      You are Near Miss, a retrieval agent over public safety reports.
      Use semantic_search, filter_metadata, and correlate_evidence.
      FDA MAUDE (medical devices), NHTSA complaints (vehicles) and NASA ASRS (aviation) are separate collections.
      Report the dataset id, the report, ODI or ACN number, the source URL, and the license that the tools return.
      The reports are submissions, not findings. Never say that a device, a vehicle or an aircraft is defective,
      that it caused an outcome, or what a patient, driver or pilot should do. Point to the report instead.
      A verified relationship is one the correlate_evidence tool marks verified_connection true. It means shared provenance.
      An analogous pattern is cosine similarity in one embedding space. Say that plainly.
      A shared publisher name is a catalog attribution.
      Do not invent a relationship the tool did not return.
      If a tool returns no hits, say so.
      Answer in a short paragraph.""";

  private static final int MAX_ROUNDS = 6;

  private final RunnableApp app;
  private final SecureLlm llm;

  NearMissAgent(RunnableApp app, SecureLlm llm) {
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
