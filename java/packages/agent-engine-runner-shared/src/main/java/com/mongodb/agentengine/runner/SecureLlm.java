package com.mongodb.agentengine.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.agentengine.sdk.BaseLLM;
import com.mongodb.agentengine.sdk.Json;
import com.mongodb.agentengine.sdk.LLMResponse;
import com.mongodb.agentengine.sdk.LLMStreamChunk;
import com.mongodb.agentengine.sdk.LLMToolSchema;
import com.mongodb.agentengine.sdk.Message;
import java.util.LinkedHashMap;
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
 *
 * <p>The keyword arguments of a call are provider options, except the reserved keys {@code tools}
 * and {@code tool_choice}, which replace the tools bound on the handle for that call.
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
    Call call = Call.of(kwargs, tools, toolChoice);
    return ExecutionScope.current().invokeLlm(llmId, model, messages, call.tools, call.toolChoice, call.options);
  }

  @Override
  public Stream<LLMStreamChunk> stream(List<Message> messages, Map<String, Object> kwargs) {
    Call call = Call.of(kwargs, tools, toolChoice);
    return ExecutionScope.current().streamLlm(llmId, model, messages, call.tools, call.toolChoice, call.options);
  }

  /**
   * One call's tools and options. The reserved keys {@code tools} and {@code tool_choice} of the
   * keyword arguments replace the handle's bound ones for this call; the rest are provider options.
   */
  private record Call(List<LLMToolSchema> tools, JsonNode toolChoice, Map<String, Object> options) {

    static Call of(Map<String, Object> kwargs, List<LLMToolSchema> boundTools, JsonNode boundChoice) {
      Map<String, Object> options = new LinkedHashMap<>(kwargs);
      Object callTools = options.remove("tools");
      Object callChoice = options.remove("tool_choice");
      options.remove("model");
      List<LLMToolSchema> tools = boundTools;
      if (callTools instanceof List<?> list) {
        tools = list.stream().map(item -> item instanceof LLMToolSchema schema ? schema : Json.mapper().convertValue(item, LLMToolSchema.class)).toList();
      }
      JsonNode choice = callChoice == null ? boundChoice : Json.mapper().valueToTree(callChoice);
      return new Call(tools, choice, options);
    }
  }
}
