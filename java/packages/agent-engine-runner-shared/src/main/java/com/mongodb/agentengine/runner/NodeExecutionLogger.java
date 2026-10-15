package com.mongodb.agentengine.runner;

import com.mongodb.agentengine.sdk.BaseExecutionCallback;
import com.mongodb.agentengine.sdk.NodeOptions;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Reports the nodes an agent runs to the orchestration engine, which stores them for the
 * platform's execution traces.
 *
 * <p>Events are posted in order from one background thread and never hold up the agent. Delivery
 * is best effort: observability must not fail an execution.
 */
public final class NodeExecutionLogger implements BaseExecutionCallback, AutoCloseable {

  private static final System.Logger LOG = System.getLogger(NodeExecutionLogger.class.getName());
  private static final int ATTEMPTS = 2;
  private static final long DRAIN_SECONDS = 10;

  private final OeClient oe;
  private final Map<String, Object> identity = new LinkedHashMap<>();
  private final Map<String, Instant> started = new ConcurrentHashMap<>();
  private final ExecutorService sender = Executors.newSingleThreadExecutor(task -> {
    Thread thread = new Thread(task, "node-execution-logger");
    thread.setDaemon(true);
    return thread;
  });

  public NodeExecutionLogger(OeClient oe, String executionId, String sessionId, String userId, String orgId, String projectId) {
    this.oe = oe;
    identity.put("execution_id", executionId);
    identity.put("session_id", sessionId);
    identity.put("user_id", userId);
    identity.put("org_id", orgId);
    identity.put("project_id", projectId);
  }

  @Override
  public void onNodeStart(String nodeName, Map<String, Object> inputs, NodeOptions options) {
    Instant now = Instant.now();
    started.put(options.runId(), now);
    send(nodeName, "started", now, options, Map.of("inputs", jsonSafe(inputs)));
  }

  @Override
  public void onNodeEnd(String nodeName, Map<String, Object> outputs, NodeOptions options) {
    Instant now = Instant.now();
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("outputs", jsonSafe(outputs));
    details.put("duration_ms", durationMs(options, now));
    send(nodeName, "success", now, options, details);
  }

  @Override
  public void onNodeError(String nodeName, String error, NodeOptions options) {
    started.remove(options.runId());
    send(nodeName, "error", Instant.now(), options, Map.of("error", error));
  }

  @Override
  public void onNodeSuspend(String nodeName, NodeOptions options) {
    Instant now = Instant.now();
    send(nodeName, "suspend", now, options, Map.of("duration_ms", durationMs(options, now)));
  }

  @Override
  public void onNodeInterrupted(String nodeName, NodeOptions options) {
    Instant now = Instant.now();
    send(nodeName, "interrupted", now, options, Map.of("duration_ms", durationMs(options, now)));
  }

  private double durationMs(NodeOptions options, Instant end) {
    if (options.durationMs() != null) {
      return options.durationMs();
    }
    Instant start = started.remove(options.runId());
    return start == null ? 0 : Duration.between(start, end).toNanos() / 1_000_000.0;
  }

  private void send(String nodeName, String status, Instant timestamp, NodeOptions options, Map<String, Object> details) {
    Map<String, Object> event = new LinkedHashMap<>(identity);
    event.put("node_name", nodeName);
    event.put("status", status);
    event.put("timestamp", timestamp.toString());
    event.put("run_id", options.runId());
    event.put("parent_run_id", options.parentRunId());
    event.putAll(details);
    sender.execute(() -> post(event));
  }

  private void post(Map<String, Object> event) {
    try {
      OeClient.Response response = oe.postJson("/node/execution", event, ATTEMPTS);
      if (!response.ok()) {
        LOG.log(System.Logger.Level.DEBUG, "The OE rejected a node event: HTTP {0}", response.status());
      }
    } catch (RuntimeException e) {
      LOG.log(System.Logger.Level.DEBUG, "Dropped a node event", e);
    }
  }

  /** Reduces a node's inputs or outputs to plain JSON values; anything else becomes its text. */
  static Object jsonSafe(Object value) {
    if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
      return value;
    }
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> safe = new LinkedHashMap<>();
      map.forEach((key, item) -> safe.put(String.valueOf(key), jsonSafe(item)));
      return safe;
    }
    if (value instanceof Collection<?> items) {
      return items.stream().map(NodeExecutionLogger::jsonSafe).toList();
    }
    return String.valueOf(value);
  }

  /** Waits for the queued events to be delivered, then stops the sender. */
  @Override
  public void close() {
    sender.shutdown();
    try {
      if (!sender.awaitTermination(DRAIN_SECONDS, TimeUnit.SECONDS)) {
        sender.shutdownNow();
      }
    } catch (InterruptedException e) {
      sender.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }
}
