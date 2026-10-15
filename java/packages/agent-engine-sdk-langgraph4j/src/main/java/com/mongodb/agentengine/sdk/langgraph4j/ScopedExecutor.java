package com.mongodb.agentengine.sdk.langgraph4j;

import com.mongodb.agentengine.runner.ExecutionScope;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An executor whose tasks run inside the execution that created it.
 *
 * <p>LangGraph4j runs a graph's nodes on worker threads, where the thread-bound {@link
 * ExecutionScope} of the turn that started the graph is not visible. A tool or model call made from
 * such a node would then have no execution to go through. Tasks given to this executor carry the
 * creating thread's execution with them.
 */
final class ScopedExecutor implements Executor {

  private static final AtomicInteger THREADS = new AtomicInteger();
  private static final ExecutorService POOL =
      Executors.newCachedThreadPool(
          task -> {
            Thread thread = new Thread(task, "langgraph4j-node-" + THREADS.incrementAndGet());
            thread.setDaemon(true);
            return thread;
          });

  private final com.mongodb.agentengine.runner.SecureExecution execution;

  /** Binds to the execution active on the calling thread. */
  ScopedExecutor() {
    this.execution = ExecutionScope.current();
  }

  @Override
  public void execute(Runnable task) {
    POOL.execute(
        () -> {
          try {
            ExecutionScope.call(execution, () -> {
              task.run();
              return null;
            });
          } catch (RuntimeException e) {
            throw e;
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
        });
  }
}
