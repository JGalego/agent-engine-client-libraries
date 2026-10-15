package com.mongodb.agentengine.runner;

/** Thrown when the orchestration engine refuses a tool or LLM call, or cannot be reached to approve it. */
public class PolicyDeniedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public PolicyDeniedException(String message) {
    super(message);
  }

  public PolicyDeniedException(String message, Throwable cause) {
    super(message, cause);
  }
}
