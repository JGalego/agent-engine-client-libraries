package com.mongodb.agentengine.sdk.langchain4j;

import com.mongodb.agentengine.sdk.BaseLLM;
import com.mongodb.agentengine.sdk.LLMResponse;
import com.mongodb.agentengine.sdk.LLMStreamChunk;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * A platform {@link BaseLLM} exposed as a LangChain4j {@link StreamingChatModel}.
 *
 * <p>Text is delivered as it arrives. A tool call is delivered with the complete response, once the
 * stream has ended. See {@link BaseLlmChatModel} for how requests map onto the {@code BaseLLM}.
 */
public final class BaseLlmStreamingChatModel implements StreamingChatModel {

  private final BaseLLM llm;

  public BaseLlmStreamingChatModel(BaseLLM llm) {
    this.llm = llm;
  }

  @Override
  public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
    List<LLMStreamChunk> chunks = new ArrayList<>();
    try (Stream<LLMStreamChunk> stream = llm.stream(Messages.toPlatform(request.messages()), ChatModelConversion.kwargs(request))) {
      for (LLMStreamChunk chunk : (Iterable<LLMStreamChunk>) stream::iterator) {
        chunks.add(chunk);
        if (chunk.content() != null && !chunk.content().isEmpty()) {
          handler.onPartialResponse(chunk.content());
        }
      }
    } catch (RuntimeException e) {
      handler.onError(e);
      return;
    }
    handler.onCompleteResponse(ChatModelConversion.toChatResponse(LLMResponse.fromChunks(chunks)));
  }
}
