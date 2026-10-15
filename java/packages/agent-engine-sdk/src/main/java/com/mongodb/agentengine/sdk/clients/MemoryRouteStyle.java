package com.mongodb.agentengine.sdk.clients;

/** Which memory route convention a backend speaks. */
public enum MemoryRouteStyle {

  /**
   * The memory server's own routes ({@code /stm/turns}, {@code /retrieval/*}). The memory server
   * serves them directly, and the orchestration engine proxy serves them too.
   */
  NATIVE,

  /**
   * The API gateway's project-scoped routes, where the core loop collapses to {@code /turns},
   * {@code /context} and a single {@code /search} that picks the source with a {@code type} body
   * field. The gateway does not expose the native paths, so a gateway-direct client needs this.
   */
  ALIASED
}
