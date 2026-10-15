package com.mongodb.agentengine.sdk;

import java.util.Optional;
import java.util.stream.Stream;

/**
 * The contract between agent code and runtimes.
 *
 * <p>Callers choose the execution mode: {@link #execute} for a final result (JSON-style) or {@link
 * #stream} for incremental events (SSE-style).
 */
public interface BaseAgent {

  /** Runs the agent to completion and returns its result. */
  AgentOutput execute(RequestContext ctx, AgentInput input);

  /**
   * Runs the agent and returns its events as they are produced. The execution is cancelled when the
   * stream is closed, so use try-with-resources.
   */
  Stream<StreamEvent> stream(RequestContext ctx, AgentInput input);

  /**
   * Checkpoints a cancelled turn so session history can serve it.
   *
   * <p>The runtime calls this while tearing down a cancelled execution. Only framework adapters
   * backed by a checkpoint store implement it; the default does nothing.
   *
   * @return the committed checkpoint, or empty when nothing was committed. It is reported to the
   *     orchestration engine so a branch can fork from it.
   */
  default Optional<CheckpointRef> commitCancelledTurn(RequestContext ctx, AgentInput input) {
    return Optional.empty();
  }
}
