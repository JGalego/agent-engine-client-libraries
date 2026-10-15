package com.mongodb.agentengine.sdk.clients;

/** Thrown when a successful response has a body that is not valid JSON or has an unexpected shape. */
public class ResponseFormatException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public ResponseFormatException(String message, Throwable cause) {
    super(message, cause);
  }
}
