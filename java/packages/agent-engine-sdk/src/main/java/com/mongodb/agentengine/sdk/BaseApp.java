package com.mongodb.agentengine.sdk;

import java.util.List;

/**
 * Base class for framework-specific SDK integrations.
 *
 * <p>Each framework SDK subclasses it and implements the abstract methods with framework-native
 * constructs. The platform runtime sets the tool and LLM wrappers before calling {@link
 * #entrypoint} so that tools and LLMs run through the secure execution layer.
 */
public abstract class BaseApp {

  private final String name;
  private volatile Object toolWrapper;
  private volatile Object llmWrapper;

  protected BaseApp(String name) {
    this.name = name;
  }

  public String name() {
    return name;
  }

  /** Returns the wrapper that routes tools through secure execution, or null if unset. */
  public Object toolWrapper() {
    return toolWrapper;
  }

  /** Sets the tool wrapper. The runtime calls this before {@link #entrypoint}. */
  public void toolWrapper(Object wrapper) {
    this.toolWrapper = wrapper;
  }

  /** Returns the wrapper that routes LLM calls through secure execution, or null if unset. */
  public Object llmWrapper() {
    return llmWrapper;
  }

  /** Sets the LLM wrapper. The runtime calls this before {@link #entrypoint}. */
  public void llmWrapper(Object wrapper) {
    this.llmWrapper = wrapper;
  }

  /** Returns all registered tool definitions. The orchestration engine reads them to run tools. */
  public abstract List<ToolDefinition> getToolDefinitions();

  /** Returns the wrapped, framework-specific tools. */
  public abstract List<Object> tools();

  /** Registers a tool on this app. */
  public abstract Object tool(Object... args);

  /** Registers the agent builder function that runtimes call to obtain a {@link BaseAgent}. */
  public abstract Object entrypoint(Object fn);
}
