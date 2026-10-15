package com.mongodb.agentengine.runner;

/** Thrown when an LLM call fails, with the provider's failure classification when it gave one. */
public class LlmInvocationException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String errorCode;

  public LlmInvocationException(String message, String errorCode) {
    super(message);
    this.errorCode = errorCode;
  }

  /** A machine-readable failure classification such as {@code llm_credential_rejected}, or null. */
  public String errorCode() {
    return errorCode;
  }
}
