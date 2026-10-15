package com.mongodb.agentengine.sdk.memory;

/** Thrown when a required identity field cannot be resolved, or conflicts with the ambient one. */
public class MemoryIdentityException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public MemoryIdentityException(String message) {
    super(message);
  }
}
