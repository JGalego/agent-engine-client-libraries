package com.mongodb.agentengine.sdk.memory;

/** Thrown for 5xx responses, and for success responses whose body is unreadable or has an unexpected shape. */
public class MemoryServerException extends MemoryApiException {

  private static final long serialVersionUID = 1L;

  public MemoryServerException(String message, Integer status, String code, String responseText) {
    super(message, status, code, responseText);
  }
}
