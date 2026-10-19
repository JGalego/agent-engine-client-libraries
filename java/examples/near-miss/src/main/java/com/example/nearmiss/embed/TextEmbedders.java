package com.example.nearmiss.embed;

import com.example.nearmiss.NearMissConfig;
import java.io.IOException;

/** Selects the text embedding model named by {@code NEAR_MISS_TEXT_PROVIDER}. */
public final class TextEmbedders {

  private TextEmbedders() {}

  public static TextEmbedder open(NearMissConfig config) throws IOException {
    String provider = config.textProvider();
    if ("openai".equalsIgnoreCase(provider)) {
      return new OpenAiTextEmbedder(config);
    }
    if ("voyage".equalsIgnoreCase(provider)) {
      return new VoyageTextEmbedder(config);
    }
    if ("minilm".equalsIgnoreCase(provider)) {
      return MiniLmEmbedder.open(config);
    }
    if ("minilm-int8".equalsIgnoreCase(provider)) {
      return MiniLmEmbedder.open(config, true);
    }
    throw new IllegalArgumentException(
        "NEAR_MISS_TEXT_PROVIDER must be minilm, minilm-int8, openai or voyage, not " + provider);
  }
}
