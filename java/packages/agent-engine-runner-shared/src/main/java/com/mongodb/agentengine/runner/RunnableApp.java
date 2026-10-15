package com.mongodb.agentengine.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.agentengine.runner.models.InvokeLlmArguments;
import com.mongodb.agentengine.sdk.BaseAgent;
import com.mongodb.agentengine.sdk.BaseApp;
import com.mongodb.agentengine.sdk.BaseLLM;
import com.mongodb.agentengine.sdk.Json;
import com.mongodb.agentengine.sdk.LLMToolSchema;
import com.mongodb.agentengine.sdk.ToolDefinition;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * A {@link BaseApp} the launcher can run: it registers tools and knows how to build its agent.
 *
 * <p>The runtime builds a fresh agent for every execution by calling {@link #buildAgent()}.
 * Framework SDKs subclass it and override {@link #buildAgent()}; a plain app registers its agent
 * builder with {@link #entrypoint(Object)}.
 *
 * <p>Agents reach models and tools through the app: {@link #llm} registers a model and returns a
 * handle that calls it through the orchestration engine, and {@link #callTool} runs a registered
 * tool the same way. Both work only while the agent runs a turn.
 */
public class RunnableApp extends BaseApp {

  /** An LLM registered with {@link #llm}: what the tool server runs when the OE routes a call to it. */
  public record RegisteredLlm(String model, BaseLLM provider) {}

  private final List<ToolDefinition> tools = new CopyOnWriteArrayList<>();
  private final Map<String, RegisteredLlm> llms = new ConcurrentHashMap<>();
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

  /**
   * Registers an LLM under the default id and returns the handle agents call it through.
   *
   * @param model the model name, which the OE records with each call
   * @param provider the model itself, which the tool server runs
   */
  public SecureLlm llm(String model, BaseLLM provider) {
    return llm(InvokeLlmArguments.DEFAULT_LLM_ID, model, provider);
  }

  /**
   * Registers a named LLM and returns the handle agents call it through. The tool server finds the
   * provider by {@code llmId} when the OE routes a call.
   */
  public SecureLlm llm(String llmId, String model, BaseLLM provider) {
    llms.put(llmId, new RegisteredLlm(model, provider));
    return new SecureLlm(llmId, model);
  }

  /** The LLM registered under {@code llmId}, if any. */
  public Optional<RegisteredLlm> registeredLlm(String llmId) {
    return Optional.ofNullable(llms.get(llmId));
  }

  /**
   * The registered tools as schemas to offer a model, the form {@link SecureLlm#bindTools(List)}
   * takes.
   */
  public List<LLMToolSchema> toolSchemas() {
    return tools.stream()
        .map(tool -> new LLMToolSchema(tool.name(), tool.description(), Json.mapper().valueToTree(tool.argsSchema()), null, null, null))
        .toList();
  }

  /** The tool registered under {@code name}, if any. */
  public Optional<ToolDefinition> findTool(String name) {
    return tools.stream().filter(tool -> tool.name().equals(name)).findFirst();
  }

  /**
   * Calls a registered tool through the orchestration engine. Only valid while the agent runs a
   * turn.
   *
   * @throws IllegalStateException outside a turn
   * @see SecureExecution#callTool
   */
  public JsonNode callTool(String name, Map<String, Object> arguments) {
    return callTool(name, arguments, null);
  }

  /** Like {@link #callTool(String, Map)}, carrying the model's id for the call so the OE can join the result to it. */
  public JsonNode callTool(String name, Map<String, Object> arguments, String toolCallId) {
    return ExecutionScope.current().callTool(name, arguments, toolCallId);
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
