package com.mongodb.agentengine.sdk.memory;

/**
 * Optional capability of a {@link MemoryRuntime}: supplying per-call ambient identity.
 *
 * <p>Only runtimes running inside the platform have per-request context. The facade detects the
 * capability with {@code instanceof}, so other runtimes need not implement it.
 */
public interface AmbientIdentityRuntime {

  /**
   * Returns the identity of the request being handled, or null outside one. It is read on every
   * call, so it may differ per request.
   */
  MemoryRequestContext requestContext();
}
