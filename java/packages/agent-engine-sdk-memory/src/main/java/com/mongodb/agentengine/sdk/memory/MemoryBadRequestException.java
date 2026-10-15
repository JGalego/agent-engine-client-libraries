package com.mongodb.agentengine.sdk.memory;

/** Thrown for 4xx responses other than auth and not-provisioned. */
public class MemoryBadRequestException extends MemoryApiException {

  private static final long serialVersionUID = 1L;

  public MemoryBadRequestException(String message, Integer status, String code, String responseText) {
    super(message, status, code, responseText);
  }
}
