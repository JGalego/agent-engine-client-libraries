package com.mongodb.agentengine.sdk.langgraph4j;

import com.mongodb.agentengine.sdk.BaseExecutionCallback;
import com.mongodb.agentengine.sdk.NodeOptions;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.action.AsyncNodeActionWithConfig;
import org.bsc.langgraph4j.hook.NodeHook;
import org.bsc.langgraph4j.state.AgentState;

/** Reports each node a graph runs to an execution callback: when it starts, and how it ends. */
final class NodeReporter<S extends AgentState> implements NodeHook.WrapCall<S> {

  private final BaseExecutionCallback callback;

  NodeReporter(BaseExecutionCallback callback) {
    this.callback = callback;
  }

  @Override
  public CompletableFuture<Map<String, Object>> applyWrap(String node, S state, RunnableConfig config, AsyncNodeActionWithConfig<S> action) {
    NodeOptions run = new NodeOptions(UUID.randomUUID().toString(), null, null, Map.of());
    callback.onNodeStart(node, state.data(), run);
    return action.apply(state, config).whenComplete((outputs, failure) -> {
      if (failure != null) {
        callback.onNodeError(node, String.valueOf(failure.getMessage()), run);
      } else {
        callback.onNodeEnd(node, outputs == null ? Map.of() : outputs, run);
      }
    });
  }
}
