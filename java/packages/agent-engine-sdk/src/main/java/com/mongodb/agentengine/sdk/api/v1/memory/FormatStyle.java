package com.mongodb.agentengine.sdk.api.v1.memory;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** How the server formats built context. */
public enum FormatStyle {
  OPENAI("openai"),
  CLAUDE("claude"),
  JINJA2("jinja2");

  private final String wireValue;

  FormatStyle(String wireValue) {
    this.wireValue = wireValue;
  }

  @JsonValue
  public String wireValue() {
    return wireValue;
  }

  /** Parses a wire value, ignoring case. */
  @JsonCreator
  public static FormatStyle fromWire(String value) {
    String lower = value.toLowerCase(Locale.ROOT);
    for (FormatStyle style : values()) {
      if (style.wireValue.equals(lower)) {
        return style;
      }
    }
    throw new IllegalArgumentException("Unknown format style: " + value);
  }
}
