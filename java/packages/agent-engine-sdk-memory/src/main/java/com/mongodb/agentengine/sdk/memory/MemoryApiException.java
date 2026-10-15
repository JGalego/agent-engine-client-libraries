package com.mongodb.agentengine.sdk.memory;

/** Base for errors from the memory backend's HTTP responses and transport. */
public class MemoryApiException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final Integer status;
  private final String code;
  private final String responseText;

  /**
   * @param status the HTTP status, or null when there was no response
   * @param code the backend's error code, if it sent one
   * @param responseText the raw response body, if there was one
   */
  public MemoryApiException(String message, Integer status, String code, String responseText) {
    super(code == null ? message : message + " (code=" + code + ")");
    this.status = status;
    this.code = code;
    this.responseText = responseText;
  }

  /** The HTTP status, or null when there was no response. */
  public Integer status() {
    return status;
  }

  /** The backend's error code, or null. */
  public String code() {
    return code;
  }

  /** The raw response body, or null. */
  public String responseText() {
    return responseText;
  }
}
