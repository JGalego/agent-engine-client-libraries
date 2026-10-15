package com.mongodb.agentengine.sdk.api.v1.memory;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** The model family built context is sized for. */
public enum ModelType {
  OPENAI("openai"),
  CLAUDE("claude"),
  ANTHROPIC("anthropic"),
  GEMINI("gemini"),
  GENERIC("generic");

  private final String wireValue;

  ModelType(String wireValue) {
    this.wireValue = wireValue;
  }

  @JsonValue
  public String wireValue() {
    return wireValue;
  }

  /** Parses a wire value, ignoring case. */
  @JsonCreator
  public static ModelType fromWire(String value) {
    String lower = value.toLowerCase(Locale.ROOT);
    for (ModelType type : values()) {
      if (type.wireValue.equals(lower)) {
        return type;
      }
    }
    throw new IllegalArgumentException("Unknown model type: " + value);
  }
}
