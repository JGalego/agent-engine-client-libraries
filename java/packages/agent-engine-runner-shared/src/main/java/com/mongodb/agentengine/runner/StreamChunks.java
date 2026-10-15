package com.mongodb.agentengine.runner;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Forwards an execution's output to the orchestration engine as it is produced, so clients that
 * stream see text arrive token by token instead of all at once at the end.
 *
 * <p>Chunks are posted in order from one background thread, so a slow engine never stalls the
 * model. Delivery is best effort: a chunk that cannot be delivered is dropped, because the
 * execution's final callback is what settles the outcome.
 */
public final class StreamChunks implements AutoCloseable {

  private static final System.Logger LOG = System.getLogger(StreamChunks.class.getName());
  private static final int ATTEMPTS = 2;
  private static final long DRAIN_SECONDS = 10;

  private final OeClient oe;
  private final String executionId;
  private final ExecutorService sender = Executors.newSingleThreadExecutor(task -> {
    Thread thread = new Thread(task, "stream-chunks");
    thread.setDaemon(true);
    return thread;
  });
  private final AtomicInteger seq = new AtomicInteger();

  public StreamChunks(OeClient oe, String executionId) {
    this.oe = oe;
    this.executionId = executionId;
  }

  /** Queues a piece of the answer. */
  public void text(String content) {
    if (content == null || content.isEmpty()) {
      return;
    }
    int number = seq.incrementAndGet();
    sender.execute(() -> post(number, content));
  }

  private void post(int number, String content) {
    Map<String, Object> chunk = new LinkedHashMap<>();
    chunk.put("execution_id", executionId);
    chunk.put("chunk_type", "text");
    chunk.put("content", content);
    chunk.put("error", "");
    chunk.put("metadata", Map.of("source", "", "tool_call_id", ""));
    chunk.put("seq", number);
    try {
      OeClient.Response response = oe.postJson("/stream/chunk", chunk, ATTEMPTS);
      if (!response.ok()) {
        LOG.log(System.Logger.Level.WARNING, "The OE rejected stream chunk {0} of execution {1}: HTTP {2}", number, executionId, response.status());
      }
    } catch (RuntimeException e) {
      LOG.log(System.Logger.Level.WARNING, "Dropped stream chunk " + number + " of execution " + executionId, e);
    }
  }

  /** Waits for the queued chunks to be delivered, then stops the sender. */
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
