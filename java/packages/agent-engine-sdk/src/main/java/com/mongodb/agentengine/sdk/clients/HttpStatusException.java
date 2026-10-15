package com.mongodb.agentengine.sdk.clients;

/**
 * Thrown for a non-successful HTTP response. It carries the status and body so callers can branch
 * on the failure mode instead of parsing the message.
 */
public class HttpStatusException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final int status;
  private final String responseText;

  public HttpStatusException(int status, String responseText) {
    super("HTTP " + status + ": " + responseText);
    this.status = status;
    this.responseText = responseText;
  }

  /** The HTTP status code. */
  public int status() {
    return status;
  }

  /** The response body. */
  public String responseText() {
    return responseText;
  }
}
