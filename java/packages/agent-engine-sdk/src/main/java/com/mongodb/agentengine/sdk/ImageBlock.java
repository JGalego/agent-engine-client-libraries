package com.mongodb.agentengine.sdk;

import java.util.Objects;

/** Image content block. */
public record ImageBlock(String url, String mimeType) implements ContentBlock {
  public ImageBlock {
    Objects.requireNonNull(url, "url");
  }
}
