package com.mongodb.agentengine.sdk;

/**
 * Token-usage metadata for an LLM call. Providers name the counts differently, so both the
 * input/output and prompt/completion pairs are kept as reported.
 *
 * @param inputTokens tokens in the request
 * @param outputTokens tokens in the response
 * @param promptTokens alias of {@code inputTokens} used by some providers
 * @param completionTokens alias of {@code outputTokens} used by some providers
 * @param totalTokens total tokens; derived from the pair above when the provider omits it
 * @param reasoningTokens output tokens the model spent reasoning; included in the output count
 * @param model the model that served the call
 */
public record LLMTokenUsage(
    Integer inputTokens,
    Integer outputTokens,
    Integer promptTokens,
    Integer completionTokens,
    Integer totalTokens,
    Integer reasoningTokens,
    String model) {

  public LLMTokenUsage {
    if (totalTokens == null) {
      Integer prompt = inputTokens != null ? inputTokens : promptTokens;
      Integer completion = outputTokens != null ? outputTokens : completionTokens;
      if (prompt != null && completion != null) {
        totalTokens = prompt + completion;
      }
    }
  }
}
