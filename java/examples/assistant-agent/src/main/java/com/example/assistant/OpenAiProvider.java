package com.example.assistant;

import com.mongodb.agentengine.sdk.BaseLLM;
import com.mongodb.agentengine.sdk.LLMResponse;
import com.mongodb.agentengine.sdk.LLMStreamChunk;
import com.mongodb.agentengine.sdk.Message;
import com.mongodb.agentengine.sdk.langchain4j.LangChain4jLLM;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The OpenAI model the tool server runs. It connects on first use, so the agent runtime, which never
 * calls it and may not hold the key, can load the app without one.
 */
final class OpenAiProvider implements BaseLLM {

  private final String model;
  private volatile LangChain4jLLM delegate;

  OpenAiProvider(String model) {
    this.model = model;
  }

  @Override
  public LLMResponse invoke(List<Message> messages, Map<String, Object> kwargs) {
    return delegate().invoke(messages, kwargs);
  }

  @Override
  public Stream<LLMStreamChunk> stream(List<Message> messages, Map<String, Object> kwargs) {
    return delegate().stream(messages, kwargs);
  }

  private LangChain4jLLM delegate() {
    LangChain4jLLM current = delegate;
    if (current == null) {
      synchronized (this) {
        if (delegate == null) {
          String key = System.getenv("OPENAI_API_KEY");
          if (key == null || key.isBlank()) {
            throw new IllegalStateException("OPENAI_API_KEY is not set in the tool server's environment");
          }
          delegate =
              LangChain4jLLM.builder()
                  .chatModel(OpenAiChatModel.builder().apiKey(key).modelName(model).build())
                  .streamingModel(OpenAiStreamingChatModel.builder().apiKey(key).modelName(model).build())
                  .build();
        }
        current = delegate;
      }
    }
    return current;
  }
}
