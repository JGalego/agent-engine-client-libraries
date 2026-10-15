package com.mongodb.agentengine.runner;

/** Thrown when a tool call fails. */
public class ToolExecutionException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public ToolExecutionException(String message) {
    super(message);
  }

  public ToolExecutionException(String message, Throwable cause) {
    super(message, cause);
  }
}
