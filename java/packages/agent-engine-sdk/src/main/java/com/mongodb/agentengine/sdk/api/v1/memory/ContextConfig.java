package com.mongodb.agentengine.sdk.api.v1.memory;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Configuration for context building. Null components take the defaults below.
 *
 * @param maxTokens token budget; default 16000
 * @param tokenBuffer tokens held back for formatting; default 500
 * @param formatStyle default {@link FormatStyle#OPENAI}
 * @param modelType default {@link ModelType#OPENAI}
 * @param similarityThreshold minimum similarity between 0 and 1; default 0
 * @param rankingWeights per-source ranking weights; default none
 * @param enabledSources sources to draw from, without duplicates; default episodic and semantic
 * @param includeMemories whether to return the selected memories; default {@code false}
 */
public record ContextConfig(
    Integer maxTokens,
    Integer tokenBuffer,
    FormatStyle formatStyle,
    ModelType modelType,
    Double similarityThreshold,
    Map<String, Double> rankingWeights,
    Set<MemorySource> enabledSources,
    Boolean includeMemories) {

  public ContextConfig {
    maxTokens = maxTokens == null ? 16000 : maxTokens;
    tokenBuffer = tokenBuffer == null ? 500 : tokenBuffer;
    formatStyle = formatStyle == null ? FormatStyle.OPENAI : formatStyle;
    modelType = modelType == null ? ModelType.OPENAI : modelType;
    similarityThreshold = similarityThreshold == null ? 0.0 : similarityThreshold;
    rankingWeights = rankingWeights == null ? Map.of() : Map.copyOf(rankingWeights);
    enabledSources =
        enabledSources == null
            ? Collections.unmodifiableSet(EnumSet.of(MemorySource.EPISODIC, MemorySource.SEMANTIC))
            : Collections.unmodifiableSet(new LinkedHashSet<>(enabledSources));
    includeMemories = includeMemories != null && includeMemories;

    if (maxTokens < 1) {
      throw new IllegalArgumentException("maxTokens must be at least 1");
    }
    if (tokenBuffer < 0) {
      throw new IllegalArgumentException("tokenBuffer must not be negative");
    }
    if (similarityThreshold < 0 || similarityThreshold > 1) {
      throw new IllegalArgumentException("similarityThreshold must be between 0 and 1");
    }
    if (enabledSources.isEmpty()) {
      throw new IllegalArgumentException("enabledSources cannot be empty");
    }
  }

  /** Returns the default configuration. */
  public static ContextConfig defaults() {
    return new ContextConfig(null, null, null, null, null, null, null, null);
  }
}
