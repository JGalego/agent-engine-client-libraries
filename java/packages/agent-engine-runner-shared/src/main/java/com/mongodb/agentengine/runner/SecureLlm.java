package com.mongodb.agentengine.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.agentengine.sdk.BaseLLM;
import com.mongodb.agentengine.sdk.LLMResponse;
import com.mongodb.agentengine.sdk.LLMStreamChunk;
import com.mongodb.agentengine.sdk.LLMToolSchema;
import com.mongodb.agentengine.sdk.Message;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * An LLM handle that routes every call through the orchestration engine.
 *
 * <p>Agents get one from {@link RunnableApp#llm}. A call made while the agent runs a turn is
 * approved and audited by the OE, which runs the model on the tool server where the provider
 * credentials live. The handle holds no provider and no secrets of its own, so it is safe to create
 * and bind in agent code.
 */
public final class SecureLlm implements BaseLLM {

  private final String llmId;
  private final String model;
  private final List<LLMToolSchema> tools;
  private final JsonNode toolChoice;

  SecureLlm(String llmId, String model) {
    this(llmId, model, List.of(), null);
  }

  private SecureLlm(String llmId, String model, List<LLMToolSchema> tools, JsonNode toolChoice) {
    this.llmId = llmId;
    this.model = model;
    this.tools = List.copyOf(tools);
    this.toolChoice = toolChoice;
  }

  /** Returns a handle that offers {@code tools} to the model on every call. */
  public SecureLlm bindTools(List<LLMToolSchema> toolSchemas) {
    return new SecureLlm(llmId, model, toolSchemas, toolChoice);
  }

  /** Returns a handle that offers {@code tools}, and forces or forbids a selection with {@code choice}. */
  public SecureLlm bindTools(List<LLMToolSchema> toolSchemas, JsonNode choice) {
    return new SecureLlm(llmId, model, toolSchemas, choice);
  }

  @Override
  public LLMResponse invoke(List<Message> messages, Map<String, Object> kwargs) {
    return ExecutionScope.current().invokeLlm(llmId, model, messages, tools, toolChoice, kwargs);
  }

  @Override
  public Stream<LLMStreamChunk> stream(List<Message> messages, Map<String, Object> kwargs) {
    return ExecutionScope.current().streamLlm(llmId, model, messages, tools, toolChoice, kwargs);
  }
}
