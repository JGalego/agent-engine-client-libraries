package com.mongodb.agentengine.sdk.langgraph4j;

import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.agentengine.runner.SessionQuery;
import com.mongodb.agentengine.sdk.Json;
import com.mongodb.agentengine.sdk.LLMToolCall;
import com.mongodb.agentengine.sdk.SessionMessage;
import com.mongodb.agentengine.sdk.SessionMessagesResponse;
import com.mongodb.agentengine.sdk.SessionSummary;
import com.mongodb.agentengine.sdk.SessionsSummaryResponse;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.bsc.langgraph4j.prebuilt.MessagesState;

/**
 * Serves session history from a LangGraph4j checkpointer.
 *
 * <p>A session's messages are the ones in its latest checkpoint. Sessions are keyed the way {@link
 * LangGraph4jAgent} writes them: the session id, scoped to the workspace when there is one.
 */
final class CheckpointSessionQuery implements SessionQuery {

  private static final int PREVIEW_CHARS = 80;

  private final BaseCheckpointSaver checkpointer;

  CheckpointSessionQuery(BaseCheckpointSaver checkpointer) {
    this.checkpointer = checkpointer;
  }

  @Override
  public SessionsSummaryResponse summaries(List<String> sessionIds, String workspaceId) {
    List<SessionSummary> summaries = new ArrayList<>();
    for (String sessionId : sessionIds) {
      List<ChatMessage> messages = chatMessages(sessionId, workspaceId);
      if (messages.isEmpty()) {
        continue;
      }
      summaries.add(new SessionSummary(sessionId, "", "", messages.size(), firstUserText(messages)));
    }
    return new SessionsSummaryResponse(summaries);
  }

  @Override
  public SessionMessagesResponse messages(String sessionId, String workspaceId) {
    List<ChatMessage> messages = chatMessages(sessionId, workspaceId);
    List<SessionMessage> converted = new ArrayList<>(messages.size());
    for (int i = 0; i < messages.size(); i++) {
      converted.add(convert(sessionId, i, messages.get(i)));
    }
    return new SessionMessagesResponse(converted);
  }

  @SuppressWarnings("unchecked")
  private List<ChatMessage> chatMessages(String sessionId, String workspaceId) {
    RunnableConfig config = RunnableConfig.builder().threadId(LangGraph4jAgent.threadId(sessionId, workspaceId)).build();
    return checkpointer
        .get(config)
        .map(Checkpoint::getState)
        .map(state -> state.get(MessagesState.MESSAGES_STATE))
        .filter(List.class::isInstance)
        .map(messages -> (List<ChatMessage>) messages)
        .orElse(List.of());
  }

  private static String firstUserText(List<ChatMessage> messages) {
    for (ChatMessage message : messages) {
      if (message instanceof UserMessage user) {
        String text = text(user);
        return text.length() <= PREVIEW_CHARS ? text : text.substring(0, PREVIEW_CHARS);
      }
    }
    return "";
  }

  private static SessionMessage convert(String sessionId, int position, ChatMessage message) {
    String id = sessionId + ":" + position;
    if (message instanceof AiMessage ai) {
      List<LLMToolCall> calls = null;
      if (ai.hasToolExecutionRequests()) {
        calls = new ArrayList<>();
        for (int i = 0; i < ai.toolExecutionRequests().size(); i++) {
          var request = ai.toolExecutionRequests().get(i);
          calls.add(new LLMToolCall(request.id(), request.name(), arguments(request.arguments()), "tool_call", i));
        }
      }
      return new SessionMessage(id, "assistant", ai.text() == null ? "" : ai.text(), "", sessionId, null, calls, null, null);
    }
    if (message instanceof ToolExecutionResultMessage result) {
      return new SessionMessage(id, "tool", result.text(), "", sessionId, result.toolName(), null, result.id(), null);
    }
    if (message instanceof SystemMessage system) {
      return new SessionMessage(id, "system", system.text(), "", sessionId, null, null, null, null);
    }
    return new SessionMessage(id, message.type().name().toLowerCase(Locale.ROOT), text(message), "", sessionId, null, null, null, null);
  }

  /** Tool arguments arrive as a JSON string; anything else is kept as text. */
  private static JsonNode arguments(String json) {
    try {
      return Json.mapper().readTree(json == null || json.isBlank() ? "{}" : json);
    } catch (IOException e) {
      return Json.mapper().getNodeFactory().textNode(json);
    }
  }

  private static String text(ChatMessage message) {
    if (message instanceof UserMessage user) {
      return user.contents().stream().filter(TextContent.class::isInstance).map(content -> ((TextContent) content).text()).collect(Collectors.joining("\n"));
    }
    return message.toString();
  }
}
