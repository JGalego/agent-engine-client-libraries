package com.mongodb.agentengine.runner;

import com.mongodb.agentengine.sdk.BaseAgent;
import com.mongodb.agentengine.sdk.BaseApp;
import com.mongodb.agentengine.sdk.ToolDefinition;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * A {@link BaseApp} the launcher can run: it registers tools and knows how to build its agent.
 *
 * <p>The runtime builds a fresh agent for every execution by calling {@link #buildAgent()}.
 * Framework SDKs subclass it and override {@link #buildAgent()}; a plain app registers its agent
 * builder with {@link #entrypoint(Object)}.
 */
public class RunnableApp extends BaseApp {

  private final List<ToolDefinition> tools = new CopyOnWriteArrayList<>();
  private volatile Supplier<BaseAgent> agentBuilder;

  public RunnableApp(String name) {
    super(name);
  }

  /** Builds the agent for one execution. */
  public BaseAgent buildAgent() {
    Supplier<BaseAgent> builder = agentBuilder;
    if (builder == null) {
      throw new IllegalStateException("App '" + name() + "' has no entrypoint: register an agent builder with entrypoint()");
    }
    return builder.get();
  }

  @Override
  public List<ToolDefinition> getToolDefinitions() {
    return List.copyOf(tools);
  }

  @Override
  public List<Object> tools() {
    return List.copyOf(tools);
  }

  /**
   * Registers a tool.
   *
   * @param args exactly one {@link ToolDefinition}
   * @return the registered definition
   * @throws IllegalArgumentException if the argument is not a {@code ToolDefinition}
   */
  @Override
  public Object tool(Object... args) {
    if (args.length != 1 || !(args[0] instanceof ToolDefinition definition)) {
      throw new IllegalArgumentException("tool() takes a single ToolDefinition");
    }
    tools.add(definition);
    return definition;
  }

  /**
   * Registers the agent builder.
   *
   * @param fn a {@code Supplier<BaseAgent>}
   * @return the supplier
   * @throws IllegalArgumentException if the argument is not a {@code Supplier}
   */
  @Override
  @SuppressWarnings("unchecked")
  public Object entrypoint(Object fn) {
    if (!(fn instanceof Supplier<?>)) {
      throw new IllegalArgumentException("entrypoint() takes a Supplier<BaseAgent>");
    }
    this.agentBuilder = (Supplier<BaseAgent>) fn;
    return fn;
  }
}
