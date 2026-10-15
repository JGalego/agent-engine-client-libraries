package com.mongodb.agentengine.sdk.memory;

/** Thrown when the memory backend cannot be reached: a connection failure or a timeout. */
public class MemoryConnectionException extends MemoryApiException {

  private static final long serialVersionUID = 1L;

  public MemoryConnectionException(String message, Integer status, String code, String responseText) {
    super(message, status, code, responseText);
  }
}
