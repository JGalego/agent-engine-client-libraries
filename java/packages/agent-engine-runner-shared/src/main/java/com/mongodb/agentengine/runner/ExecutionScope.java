package com.mongodb.agentengine.runner;

import java.util.concurrent.Callable;

/**
 * The execution an agent is currently running in.
 *
 * <p>The agent runtime binds a {@link SecureExecution} to the thread that runs a turn, so tool and
 * LLM calls made by agent code reach the orchestration engine for the right execution. Work an agent
 * hands to another thread must carry the scope along with {@link #wrap(Runnable)}.
 */
public final class ExecutionScope {

  private static final ThreadLocal<SecureExecution> CURRENT = new ThreadLocal<>();

  private ExecutionScope() {}

  /**
   * The current execution.
   *
   * @throws IllegalStateException outside an execution, such as at class-initialization time
   */
  public static SecureExecution current() {
    SecureExecution execution = CURRENT.get();
    if (execution == null) {
      throw new IllegalStateException("No execution is active on this thread: tool and LLM calls are only possible while the agent runs a turn");
    }
    return execution;
  }

  /** Whether an execution is active on this thread. */
  public static boolean isActive() {
    return CURRENT.get() != null;
  }

  /** Runs {@code body} with {@code execution} as the current execution, restoring the previous one after. */
  public static <T> T call(SecureExecution execution, Callable<T> body) throws Exception {
    SecureExecution previous = CURRENT.get();
    CURRENT.set(execution);
    try {
      return body.call();
    } finally {
      if (previous == null) {
        CURRENT.remove();
      } else {
        CURRENT.set(previous);
      }
    }
  }

  /** Returns a task that runs {@code task} in the execution active now, on whichever thread runs it. */
  public static Runnable wrap(Runnable task) {
    SecureExecution captured = current();
    return () -> {
      try {
        call(captured, () -> {
          task.run();
          return null;
        });
      } catch (RuntimeException e) {
        throw e;
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    };
  }
}
