package com.mongodb.agentengine.sdk;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/**
 * Explicit provider and model options passed with an LLM invocation.
 *
 * @param extras provider-specific options outside the standard set (for example {@code
 *     temperature}); they round-trip as top-level fields
 */
public record LLMInvocationOptions(
    Integer maxTokens,
    JsonNode responseFormat,
    Double topP,
    Integer topK,
    Double frequencyPenalty,
    Double presencePenalty,
    Long seed,
    Double timeout,
    Boolean parallelToolCalls,
    String reasoningEffort,
    String verbosity,
    @JsonAnySetter Map<String, Object> extras) {

  public LLMInvocationOptions {
    extras = extras == null ? Map.of() : Map.copyOf(extras);
  }

  @Override
  @JsonAnyGetter
  public Map<String, Object> extras() {
    return extras;
  }

  /** Converts to the keyword arguments handed to the underlying model call. */
  public Map<String, Object> toModelKwargs() {
    return Json.mapper().convertValue(this, new TypeReference<>() {});
  }
}
