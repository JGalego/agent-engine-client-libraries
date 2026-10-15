package com.mongodb.agentengine.sdk.memory;

/** Thrown when the project's memory runtime is not yet reachable. */
public class MemoryNotProvisionedException extends MemoryApiException {

  private static final long serialVersionUID = 1L;

  public MemoryNotProvisionedException(String message, Integer status, String code, String responseText) {
    super(message, status, code, responseText);
  }
}
