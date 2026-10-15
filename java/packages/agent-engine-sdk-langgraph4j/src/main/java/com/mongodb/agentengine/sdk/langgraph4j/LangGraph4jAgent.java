package com.mongodb.agentengine.sdk.langgraph4j;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mongodb.agentengine.runner.ExecutionScope;
import com.mongodb.agentengine.sdk.AgentInput;
import com.mongodb.agentengine.sdk.AgentOutput;
import com.mongodb.agentengine.sdk.BaseAgent;
import com.mongodb.agentengine.sdk.RequestContext;
import com.mongodb.agentengine.sdk.StreamEvent;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import java.util.Map;
import java.util.stream.Stream;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.NodeOutput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.prebuilt.MessagesState;

/**
 * Runs a compiled LangGraph4j graph as a platform agent.
 *
 * <p>Each execution adds the caller's message to the graph's conversation and runs the graph; the
 * answer is the text of the last AI message. The graph's checkpointer keeps the conversation between
 * turns, keyed by the session and workspace so that sessions never share state.
 *
 * @param <S> the graph's state, which holds LangChain4j chat messages
 */
public final class LangGraph4jAgent<S extends MessagesState<ChatMessage>> implements BaseAgent {

  private final CompiledGraph<S> graph;

  public LangGraph4jAgent(CompiledGraph<S> graph) {
    this.graph = graph;
  }

  @Override
  public AgentOutput execute(RequestContext ctx, AgentInput input) {
    S state = graph.invoke(graphInput(input), config(ctx, true)).orElseThrow(() -> new IllegalStateException("The graph produced no state"));
    return new AgentOutput(JsonNodeFactory.instance.textNode(answer(state)));
  }

  /**
   * Streams the graph's progress: one event per node, named {@code node}, whose data holds the node
   * and the text of the last message in the state.
   */
  @Override
  public Stream<StreamEvent> stream(RequestContext ctx, AgentInput input) {
    return graph.stream(graphInput(input), config(ctx, true)).stream().map(LangGraph4jAgent::event);
  }

  private static StreamEvent event(NodeOutput<? extends MessagesState<ChatMessage>> output) {
    ObjectNode data = JsonNodeFactory.instance.objectNode().put("node", output.node());
    output.state().lastMessage().ifPresent(message -> data.put("message", text(message)));
    return new StreamEvent(data, "node");
  }

  private static Map<String, Object> graphInput(AgentInput input) {
    JsonNode message = input.payload().path("message");
    return Map.of(MessagesState.MESSAGES_STATE, UserMessage.from(message.asText("")));
  }

  /** The conversation key: the session, scoped to the workspace so agents sharing a store cannot collide. */
  static RunnableConfig config(RequestContext ctx, boolean carryExecution) {
    String session = ctx.sessionId() != null && !ctx.sessionId().isBlank() ? ctx.sessionId() : ctx.executionId();
    RunnableConfig.Builder config = RunnableConfig.builder().threadId(threadId(session, ctx.workspaceId()));
    if (carryExecution && ExecutionScope.isActive()) {
      config.emitterExecutor(new ScopedExecutor());
    }
    return config.build();
  }

  /** The checkpointer's thread for a session, scoped to the workspace when there is one. */
  static String threadId(String session, String workspaceId) {
    return workspaceId == null || workspaceId.isBlank() ? session : session + ":" + workspaceId;
  }

  private static String answer(MessagesState<ChatMessage> state) {
    ChatMessage last = state.lastMessage().orElseThrow(() -> new IllegalStateException("The graph ended with no messages"));
    if (last instanceof AiMessage ai && ai.text() != null) {
      return ai.text();
    }
    return text(last);
  }

  private static String text(ChatMessage message) {
    return switch (message.type()) {
      case AI -> ((AiMessage) message).text() == null ? "" : ((AiMessage) message).text();
      case USER -> ((UserMessage) message).hasSingleText() ? ((UserMessage) message).singleText() : message.toString();
      default -> message.toString();
    };
  }
}
