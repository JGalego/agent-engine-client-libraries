package com.mongodb.agentengine.sdk.memory;

/**
 * Thrown when an operation is unavailable in the active mode or on the connected platform.
 *
 * <p>It covers client-side gaps, such as a runtime injected without a CRUD client, and capability
 * gaps only the platform can report on the custom-type routes: a bare 404 or 405 (the platform is
 * too old to serve them) or a structured 400 saying custom memory types are disabled.
 */
public class MemoryNotSupportedException extends MemoryClientException {

  private static final long serialVersionUID = 1L;

  public MemoryNotSupportedException(String message) {
    super(message);
  }

  public MemoryNotSupportedException(String message, Throwable cause) {
    super(message, cause);
  }
}
