package com.example.embeddingzoo;

import com.example.embeddingzoo.embed.TextEmbedder;
import com.example.embeddingzoo.embed.TextEmbedders;
import com.example.embeddingzoo.store.AtlasVectorStore;
import com.example.embeddingzoo.store.InMemoryVectorStore;
import com.example.embeddingzoo.store.VectorStore;
import java.io.IOException;
import java.util.Map;

/**
 * Opens the store and embedder on first tool call.
 *
 * <p>The agent class can load without MongoDB or a model file. The first tool call is what needs them.
 */
final class ZooServices {

  private static volatile ZooToolkit toolkit;

  private ZooServices() {}

  static ZooToolkit toolkit() {
    ZooToolkit current = toolkit;
    if (current == null) {
      synchronized (ZooServices.class) {
        if (toolkit == null) {
          toolkit = open(ZooConfig.load());
        }
        current = toolkit;
      }
    }
    return current;
  }

  static ZooToolkit open(ZooConfig config) {
    try {
      TextEmbedder embedder = TextEmbedders.open(config);
      VectorStore store = config.atlasConfigured()
          ? AtlasVectorStore.connect(config)
          : new InMemoryVectorStore();
      return new ZooToolkit(config, store, embedder);
    } catch (IOException e) {
      throw new IllegalStateException(e.getMessage(), e);
    }
  }

  static Object dispatch(ZooToolkit toolkit, String name, Map<String, Object> arguments) {
    return switch (name) {
      case "semantic_search" -> toolkit.semanticSearch(arguments);
      case "filter_metadata" -> toolkit.filterMetadata(arguments);
      case "correlate_evidence" -> toolkit.correlate(arguments);
      default -> Map.of("error", "Unknown tool " + name);
    };
  }
}
