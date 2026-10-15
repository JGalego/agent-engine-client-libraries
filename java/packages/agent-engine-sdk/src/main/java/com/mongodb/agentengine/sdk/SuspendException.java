package com.mongodb.agentengine.sdk;

import java.util.Map;
import java.util.Objects;

/**
 * Thrown by an agent or a tool to pause the execution until a human decides how to continue.
 *
 * <p>The runtime reports the execution as suspended and the platform shows {@link #reason()} and
 * {@link #context()} to a reviewer. When the reviewer resumes it, the agent runs again with {@link
 * RequestContext#resume()} set and the decision in {@link RequestContext#resumeData()}.
 */
public class SuspendException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String reason;
  private final Map<String, Object> context;
  private final Map<String, Object> metadata;

  /**
   * @param reason why the execution suspends, for example {@code awaiting_human_review}
   * @param context what the reviewer needs to decide
   */
  public SuspendException(String reason, Map<String, Object> context) {
    this(reason, context, Map.of());
  }

  /**
   * @param metadata framework-owned state the platform hands back unchanged in {@link
   *     RequestContext#metadata()} on resume
   */
  public SuspendException(String reason, Map<String, Object> context, Map<String, Object> metadata) {
    super(reason);
    this.reason = Objects.requireNonNull(reason, "reason");
    this.context = Map.copyOf(context);
    this.metadata = Map.copyOf(metadata);
  }

  public String reason() {
    return reason;
  }

  public Map<String, Object> context() {
    return context;
  }

  public Map<String, Object> metadata() {
    return metadata;
  }
}
