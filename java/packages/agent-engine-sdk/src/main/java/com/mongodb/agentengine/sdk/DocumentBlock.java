package com.mongodb.agentengine.sdk;

import java.util.Objects;

/** Document or file content block. */
public record DocumentBlock(String url, String mimeType, String filename) implements ContentBlock {
  public DocumentBlock {
    Objects.requireNonNull(url, "url");
  }
}
