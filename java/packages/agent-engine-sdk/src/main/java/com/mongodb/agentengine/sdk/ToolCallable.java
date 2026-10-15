package com.mongodb.agentengine.sdk;

import java.util.Map;

/** The function a {@link ToolDefinition} runs when the platform invokes the tool. */
@FunctionalInterface
public interface ToolCallable {

  /**
   * Runs the tool.
   *
   * @param arguments the call arguments, matching the tool's {@code argsSchema}
   * @return the tool result; it must be serializable to JSON
   */
  Object call(Map<String, Object> arguments) throws Exception;
}
