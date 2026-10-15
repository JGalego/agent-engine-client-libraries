package com.mongodb.agentengine.runner.server;

/** An error a handler reports to the caller as an HTTP status and a {@code detail} message. */
public class HttpError extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final int status;

  public HttpError(int status, String detail) {
    super(detail);
    this.status = status;
  }

  public int status() {
    return status;
  }
}
