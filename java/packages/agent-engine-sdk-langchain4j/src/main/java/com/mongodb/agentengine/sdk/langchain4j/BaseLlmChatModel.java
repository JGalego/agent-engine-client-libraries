package com.mongodb.agentengine.sdk.langchain4j;

import com.mongodb.agentengine.sdk.BaseLLM;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

/**
 * A platform {@link BaseLLM} exposed as a LangChain4j {@link ChatModel}.
 *
 * <p>It is the inverse of {@link LangChain4jLLM}. Wrap a platform-routed model with it and any
 * LangChain4j or LangGraph4j code, such as an agent executor, runs on that model unchanged: every
 * call still goes through whatever the {@code BaseLLM} does, which for the runtime's {@code
 * SecureLlm} means the orchestration engine.
 *
 * <p>A request's sampling parameters, stop sequences, tools and tool choice travel as the keyword
 * arguments {@link LangChain4jLLM} documents.
 */
public final class BaseLlmChatModel implements ChatModel {

  private final BaseLLM llm;

  public BaseLlmChatModel(BaseLLM llm) {
    this.llm = llm;
  }

  @Override
  public ChatResponse doChat(ChatRequest request) {
    return ChatModelConversion.toChatResponse(llm.invoke(Messages.toPlatform(request.messages()), ChatModelConversion.kwargs(request)));
  }
}
