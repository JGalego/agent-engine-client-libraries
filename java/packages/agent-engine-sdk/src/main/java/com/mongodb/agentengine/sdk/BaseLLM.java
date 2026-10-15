package com.mongodb.agentengine.sdk;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Framework-neutral LLM protocol. The tool pod uses it at runtime.
 *
 * <p>The {@code kwargs} of a call are provider options such as {@code max_tokens}, {@code
 * temperature} and {@code stop}. Two keys are reserved for tools: {@code tools} (a list of {@link
 * LLMToolSchema}s) offers tools to the model for that call, and {@code tool_choice} ({@code auto},
 * {@code required}, {@code none}, or a tool name) says whether it may or must call one.
 */
public interface BaseLLM {

  /** Sends the messages to the model and returns its complete response. */
  LLMResponse invoke(List<Message> messages, Map<String, Object> kwargs);

  /** Sends the messages to the model and returns the response as it is generated. */
  Stream<LLMStreamChunk> stream(List<Message> messages, Map<String, Object> kwargs);

  /** Like {@link #invoke(List, Map)} with no extra model arguments. */
  default LLMResponse invoke(List<Message> messages) {
    return invoke(messages, Map.of());
  }

  /** Like {@link #stream(List, Map)} with no extra model arguments. */
  default Stream<LLMStreamChunk> stream(List<Message> messages) {
    return stream(messages, Map.of());
  }
}
