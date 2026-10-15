package com.mongodb.agentengine.sdk;

import java.util.Objects;

/** Text content block. */
public record TextBlock(String text) implements ContentBlock {
  public TextBlock {
    Objects.requireNonNull(text, "text");
  }
}
