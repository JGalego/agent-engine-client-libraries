package com.mongodb.agentengine.sdk.api.v1.memory;

import com.fasterxml.jackson.annotation.JsonValue;

/** Where a retrieved memory chunk came from. */
public enum MemorySource {
  STM("stm"),
  EPISODIC("episodic"),
  SEMANTIC("semantic"),
  TAXONOMIC("taxonomic"),
  PROCEDURAL("procedural");

  private final String wireValue;

  MemorySource(String wireValue) {
    this.wireValue = wireValue;
  }

  @JsonValue
  public String wireValue() {
    return wireValue;
  }
}
