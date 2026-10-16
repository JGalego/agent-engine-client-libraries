package com.example.nearmiss;

import com.example.nearmiss.embed.TextEmbedder;
import com.example.nearmiss.embed.TextEmbedders;
import com.example.nearmiss.store.AtlasVectorStore;
import com.example.nearmiss.store.InMemoryVectorStore;
import com.example.nearmiss.store.VectorStore;
import java.io.IOException;
import java.util.Map;

/**
 * Opens the store and embedder on first tool call.
 *
 * <p>The agent class can load without MongoDB or a model file. The first tool call is what needs them.
 */
final class NearMissServices {

  private static volatile NearMissToolkit toolkit;

  private NearMissServices() {}

  static NearMissToolkit toolkit() {
    NearMissToolkit current = toolkit;
    if (current == null) {
      synchronized (NearMissServices.class) {
        if (toolkit == null) {
          toolkit = open(NearMissConfig.load());
        }
        current = toolkit;
      }
    }
    return current;
  }

  static NearMissToolkit open(NearMissConfig config) {
    try {
      TextEmbedder embedder = TextEmbedders.open(config);
      VectorStore store = config.atlasConfigured()
          ? AtlasVectorStore.connect(config)
          : new InMemoryVectorStore();
      return new NearMissToolkit(config, store, embedder);
    } catch (IOException e) {
      throw new IllegalStateException(e.getMessage(), e);
    }
  }

  static Object dispatch(NearMissToolkit toolkit, String name, Map<String, Object> arguments) {
    return switch (name) {
      case "semantic_search" -> toolkit.semanticSearch(arguments);
      case "filter_metadata" -> toolkit.filterMetadata(arguments);
      case "correlate_evidence" -> toolkit.correlate(arguments);
      default -> Map.of("error", "Unknown tool " + name);
    };
  }
}
