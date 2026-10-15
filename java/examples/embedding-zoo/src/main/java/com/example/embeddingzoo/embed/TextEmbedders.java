package com.example.embeddingzoo.embed;

import com.example.embeddingzoo.ZooConfig;
import java.io.IOException;

/** Selects the text embedding model named by {@code EMBEDDING_ZOO_TEXT_PROVIDER}. */
public final class TextEmbedders {

  private TextEmbedders() {}

  public static TextEmbedder open(ZooConfig config) throws IOException {
    String provider = config.textProvider();
    if ("openai".equalsIgnoreCase(provider)) {
      return new OpenAiTextEmbedder(config);
    }
    if ("minilm".equalsIgnoreCase(provider)) {
      return MiniLmEmbedder.open(config);
    }
    throw new IllegalArgumentException(
        "EMBEDDING_ZOO_TEXT_PROVIDER must be minilm or openai, not " + provider);
  }
}
