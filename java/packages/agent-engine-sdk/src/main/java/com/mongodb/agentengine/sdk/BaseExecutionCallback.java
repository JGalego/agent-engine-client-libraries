package com.mongodb.agentengine.sdk;

import java.util.Map;

/**
 * Framework-neutral callback for observability during agent execution.
 *
 * <p>The runtime injects an implementation that forwards node events to the orchestration engine
 * for logging, and framework adapters bridge their native callback systems to it. Every method has
 * a no-op default, so implement only the events you care about; new events added here will not
 * break existing implementations.
 */
public interface BaseExecutionCallback {

  default void onNodeStart(String nodeName, Map<String, Object> inputs, NodeOptions options) {}

  default void onNodeEnd(String nodeName, Map<String, Object> outputs, NodeOptions options) {}

  default void onNodeError(String nodeName, String error, NodeOptions options) {}

  default void onNodeSuspend(String nodeName, NodeOptions options) {}

  default void onNodeInterrupted(String nodeName, NodeOptions options) {}
}
