package com.mongodb.agentengine.runner.models;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.agentengine.sdk.LLMToolSchema;
import com.mongodb.agentengine.sdk.Message;
import java.util.List;
import java.util.Objects;

/**
 * The arguments of an {@code invoke_llm} call, forwarded through the orchestration engine to the
 * tool server.
 *
 * @param model the model name
 * @param llmId the id the LLM was registered under; {@code __default__} when it was not named
 * @param stop stop sequences for the provider
 * @param tools schemas of the tools offered to the model
 * @param toolChoice a forced tool selection, such as a function name
 * @param options provider options such as {@code max_tokens}, as a JSON object
 * @param stream whether the OE should route a real streaming call
 */
public record InvokeLlmArguments(
    String model,
    List<Message> messages,
    String llmId,
    @JsonAlias("stop_sequences") List<String> stop,
    List<LLMToolSchema> tools,
    JsonNode toolChoice,
    JsonNode options,
    boolean stream) {

  /** The id LLMs are registered under when the app gives none. */
  public static final String DEFAULT_LLM_ID = "__default__";

  public InvokeLlmArguments {
    Objects.requireNonNull(model, "model");
    Objects.requireNonNull(messages, "messages");
    llmId = llmId == null ? DEFAULT_LLM_ID : llmId;
  }
}
