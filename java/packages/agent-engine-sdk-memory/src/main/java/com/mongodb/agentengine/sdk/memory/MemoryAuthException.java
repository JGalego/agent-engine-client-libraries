package com.mongodb.agentengine.sdk.memory;

/** Thrown for 401 and 403 responses. */
public class MemoryAuthException extends MemoryApiException {

  private static final long serialVersionUID = 1L;

  public MemoryAuthException(String message, Integer status, String code, String responseText) {
    super(message, status, code, responseText);
  }
}
