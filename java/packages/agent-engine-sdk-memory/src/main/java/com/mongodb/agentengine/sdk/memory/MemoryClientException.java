package com.mongodb.agentengine.sdk.memory;

/** Base for errors detected before any HTTP request is made; they have no status or body. */
public class MemoryClientException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public MemoryClientException(String message) {
    super(message);
  }

  public MemoryClientException(String message, Throwable cause) {
    super(message, cause);
  }
}
