package com.mongodb.agentengine.sdk.memory;

/**
 * Thrown when a core-loop request returns 404, which hints at a route-shape mismatch.
 *
 * <p>The SDK picks the route shape from the presence of a project id: set means project-scoped
 * gateway routes, empty means flat orchestration-engine routes. A 404 on a core-loop request most
 * often means the shape does not match the backend the base URL points at, so the message says
 * which way to adjust. It extends {@link MemoryBadRequestException}, so existing 4xx handling
 * still catches it.
 */
public class MemoryRouteNotFoundException extends MemoryBadRequestException {

  private static final long serialVersionUID = 1L;

  public MemoryRouteNotFoundException(
      String message, Integer status, String code, String responseText) {
    super(message, status, code, responseText);
  }
}
