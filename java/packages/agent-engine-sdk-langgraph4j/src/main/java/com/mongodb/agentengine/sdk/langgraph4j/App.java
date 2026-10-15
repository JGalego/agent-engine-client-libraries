package com.mongodb.agentengine.sdk.langgraph4j;

import com.mongodb.agentengine.runner.ExecutionScope;
import com.mongodb.agentengine.runner.RunnableApp;
import com.mongodb.agentengine.sdk.BaseAgent;
import com.mongodb.agentengine.sdk.BaseLLM;
import com.mongodb.agentengine.sdk.ToolDefinition;
import com.mongodb.agentengine.sdk.langchain4j.BaseLlmChatModel;
import com.mongodb.agentengine.sdk.langchain4j.BaseLlmStreamingChatModel;
import com.mongodb.agentengine.sdk.langchain4j.ToolSchemas;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import java.util.function.Supplier;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.agentexecutor.AgentExecutor;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.state.AgentState;

/**
 * A LangGraph4j agent for the Atlas Agent Engine.
 *
 * <p>Register tools with {@link #tool(Object...)} and models with {@link #chatModel}, build a graph
 * from them, and register the graph with {@link #graph}. Everything the graph does with those tools
 * and models runs through the orchestration engine.
 *
 * <pre>{@code
 * public static final App app = create();
 *
 * private static App create() {
 *   App app = new App("assistant");
 *   app.tool(ToolDefinition.builder("get_current_date", "Today's date", schema, args -> LocalDate.now().toString()).build());
 *   ChatModel model = app.chatModel("gpt-4o-mini", provider);
 *   app.graph(() -> app.compile(app.agentExecutor(model).build()));
 *   return app;
 * }
 * }</pre>
 *
 * <p>The graph is rebuilt for every execution. Conversation state lives in the app's checkpointer,
 * which is shared by those graphs: it is held in memory, so it lasts as long as the process does. The
 * platform's session list and history views read from it too.
 */
public class App extends RunnableApp {

  private final BaseCheckpointSaver checkpointer = new MemorySaver();
  private volatile Supplier<?> graphBuilder;

  public App(String name) {
    super(name);
    sessionQuery(new CheckpointSessionQuery(checkpointer));
  }

  /**
   * Registers a model and returns a LangChain4j chat model over it. Calls through the returned model
   * go through the orchestration engine, which runs {@code provider} on the tool server.
   */
  public ChatModel chatModel(String model, BaseLLM provider) {
    return new BaseLlmChatModel(llm(model, provider));
  }

  /** Like {@link #chatModel}, for code that wants a streaming chat model. */
  public BaseLlmStreamingChatModel streamingChatModel(String model, BaseLLM provider) {
    return new BaseLlmStreamingChatModel(llm(model, provider));
  }

  /**
   * Starts LangGraph4j's agent executor on {@code model}, with every tool registered on this app.
   * The tools run through the orchestration engine. Set a system message or other options on the
   * returned builder, then {@code build()} it.
   *
   * @return a builder, before any option beyond the model and tools is set
   */
  public AgentExecutor.Builder agentExecutor(ChatModel model) {
    AgentExecutor.Builder builder = AgentExecutor.builder().chatModel(model);
    PlatformToolExecutor executor = new PlatformToolExecutor(this);
    for (ToolDefinition tool : getToolDefinitions()) {
      builder.toolSpecification(ToolSchemas.toToolSpecification(tool), executor);
    }
    return builder;
  }

  /**
   * Compiles a graph with this app's checkpointer. A conversation's checkpoints are kept when a run
   * ends, so the next turn of the same session continues from them. While an execution is running,
   * the nodes the graph runs are reported to the orchestration engine for the platform's traces.
   */
  public <S extends AgentState> CompiledGraph<S> compile(StateGraph<S> graph) throws GraphStateException {
    if (ExecutionScope.isActive()) {
      graph.addWrapCallNodeHook(new NodeReporter<>(ExecutionScope.current().nodes()));
    }
    return graph.compile(CompileConfig.builder().checkpointSaver(checkpointer).releaseThread(false).build());
  }

  /** The checkpointer conversations are kept in. */
  public BaseCheckpointSaver checkpointer() {
    return checkpointer;
  }

  /** Registers the graph builder; see {@link #entrypoint(Object)}. */
  public <S extends MessagesState<ChatMessage>> void graph(Supplier<CompiledGraph<S>> builder) {
    entrypoint(builder);
  }

  /**
   * Registers the agent builder.
   *
   * @param fn a {@code Supplier} of either a {@link BaseAgent} or a compiled LangGraph4j graph over
   *     chat messages
   */
  @Override
  public Object entrypoint(Object fn) {
    if (!(fn instanceof Supplier<?> supplier)) {
      throw new IllegalArgumentException("entrypoint() takes a Supplier of a BaseAgent or a CompiledGraph");
    }
    this.graphBuilder = supplier;
    return fn;
  }

  @Override
  @SuppressWarnings({"unchecked", "rawtypes"})
  public BaseAgent buildAgent() {
    Supplier<?> builder = graphBuilder;
    if (builder == null) {
      throw new IllegalStateException("App '" + name() + "' has no entrypoint: register a graph with graph()");
    }
    Object built = builder.get();
    if (built instanceof BaseAgent agent) {
      return agent;
    }
    if (built instanceof CompiledGraph graph) {
      return new LangGraph4jAgent(graph);
    }
    throw new IllegalStateException("The entrypoint must build a BaseAgent or a CompiledGraph, not " + (built == null ? "null" : built.getClass().getName()));
  }
}
