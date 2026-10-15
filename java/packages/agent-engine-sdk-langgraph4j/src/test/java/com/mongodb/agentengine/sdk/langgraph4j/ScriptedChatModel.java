package com.mongodb.agentengine.sdk.langgraph4j;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

/** A chat model that plays back a script of responses and records every request it receives. */
final class ScriptedChatModel implements ChatModel {

  final List<ChatRequest> requests = new ArrayList<>();
  private final Queue<ChatResponse> script = new ArrayDeque<>();

  ScriptedChatModel answer(String text) {
    script.add(ChatResponse.builder().aiMessage(AiMessage.from(text)).build());
    return this;
  }

  ScriptedChatModel callTool(String id, String name, String argumentsJson) {
    script.add(ChatResponse.builder().aiMessage(AiMessage.from(ToolExecutionRequest.builder().id(id).name(name).arguments(argumentsJson).build())).build());
    return this;
  }

  @Override
  public ChatResponse doChat(ChatRequest request) {
    requests.add(request);
    ChatResponse next = script.poll();
    if (next == null) {
      throw new IllegalStateException("The script ran out of responses");
    }
    return next;
  }

  /** The messages of the most recent request. */
  List<ChatMessage> lastMessages() {
    return requests.get(requests.size() - 1).messages();
  }
}
