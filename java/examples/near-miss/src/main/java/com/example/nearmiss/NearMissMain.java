package com.example.nearmiss;

import com.example.nearmiss.embed.TextEmbedder;
import com.example.nearmiss.embed.TextEmbedders;
import com.example.nearmiss.ingest.AsrsIngestor;
import com.example.nearmiss.ingest.HttpTexts;
import com.example.nearmiss.ingest.MaudeIngestor;
import com.example.nearmiss.ingest.NhtsaIngestor;
import com.example.nearmiss.store.AtlasVectorStore;
import com.example.nearmiss.store.InMemoryVectorStore;
import com.example.nearmiss.store.StoredRecord;
import com.example.nearmiss.store.VectorStore;
import com.mongodb.agentengine.sdk.Json;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Command line for download, ingest, and retrieval. */
public final class NearMissMain {

  private NearMissMain() {}

  public static void main(String[] args) throws Exception {
    System.setProperty("java.awt.headless", "true");
    if (args.length == 0) {
      System.err.println("Usage: download | ingest | search <query> | correlate <query> | demo");
      System.exit(2);
      return;
    }
    NearMissConfig config = NearMissConfig.load();
    switch (args[0]) {
      case "download" -> download(config);
      case "ingest" -> ingest(config);
      case "search" -> {
        requireQuery(args);
        System.out.println(Json.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(
            open(config).semanticSearch(Map.of("query", args[1], "limit", 5))));
      }
      case "correlate" -> {
        requireQuery(args);
        System.out.println(Json.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(
            open(config).correlate(Map.of("query", args[1]))));
      }
      case "demo" -> demo(config);
      default -> throw new IllegalArgumentException("Unknown command " + args[0]);
    }
  }

  private static void download(NearMissConfig config) throws IOException {
    Files.createDirectories(config.modelDir());
    Path vocab = config.modelDir().resolve("vocab.txt");
    Path model = config.modelDir().resolve("model.onnx");
    if (!Files.isRegularFile(vocab)) {
      HttpTexts.download(URI.create(Datasets.VOCAB_URL), vocab);
    }
    if (!Files.isRegularFile(model)) {
      System.err.println("Downloading MiniLM ONNX (about 90 MB)");
      HttpTexts.download(URI.create(Datasets.ONNX_URL), model);
    }
    Path quantized = config.modelDir().resolve("model_quantized.onnx");
    if (!Files.isRegularFile(quantized)) {
      System.err.println("Downloading the int8 MiniLM ONNX (about 23 MB)");
      HttpTexts.download(URI.create(Datasets.ONNX_INT8_URL), quantized);
    }
  }

  private static void ingest(NearMissConfig config) throws IOException {
    try (NearMissToolkit toolkit = prepared(config, true)) {
      System.err.println(config.atlasConfigured()
          ? "Records are in Atlas."
          : "The in-memory store is discarded when this process exits. Run demo to search in one process, or set MONGODB_URI.");
      System.out.println(Json.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
          "text_model", toolkit.embedder().modelId(),
          "store", config.atlasConfigured() ? "atlas" : "in_memory")));
    }
  }

  private static void demo(NearMissConfig config) throws Exception {
    try (NearMissToolkit toolkit = prepared(config, true)) {
      Map<String, Object> out = new LinkedHashMap<>();
      out.put("device_search", toolkit.semanticSearch(Map.of("query", "insulin pump delivered too much insulin", "limit", 3)));
      out.put("vehicle_search", toolkit.semanticSearch(Map.of("query", "car lost power while driving on the highway", "limit", 3)));
      out.put("aviation_search", toolkit.semanticSearch(Map.of("query", "engine lost power during climb", "limit", 3)));
      out.put("correlation", toolkit.correlate(Map.of("query", "sudden loss of power with no warning")));
      System.out.println(Json.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(out));
    }
  }

  private static NearMissToolkit open(NearMissConfig config) throws IOException {
    if (config.atlasConfigured()) {
      return prepared(config, false);
    }
    return prepared(config, true);
  }

  private static NearMissToolkit prepared(NearMissConfig config, boolean ingest) throws IOException {
    if (ingest) {
      download(config);
    }
    TextEmbedder embedder = TextEmbedders.open(config);
    VectorStore store = openStore(config);
    if (ingest) {
      List<StoredRecord> records = collect(config, embedder);
      store.upsert(records);
      store.ensureIndexes(embedder.dimensions());
      System.err.println("Upserted " + records.size() + " records");
      if (store instanceof AtlasVectorStore atlas) {
        boolean ready = atlas.awaitIndex(AtlasVectorStore.DurationLike.seconds(90));
        System.err.println(ready
            ? "Atlas vector index is READY."
            : "Atlas vector index is not READY yet. Searches use an exact cosine scan until it is, and say so.");
      }
    }
    return new NearMissToolkit(config, store, embedder);
  }

  private static List<StoredRecord> collect(NearMissConfig config, TextEmbedder embedder) throws IOException {
    List<StoredRecord> records = new ArrayList<>();
    records.addAll(MaudeIngestor.load(config, embedder));
    records.addAll(NhtsaIngestor.load(config, embedder));
    records.addAll(AsrsIngestor.load(config, embedder));
    return records;
  }

  private static VectorStore openStore(NearMissConfig config) {
    if (config.atlasConfigured()) {
      return AtlasVectorStore.connect(config);
    }
    System.err.println("MONGODB_URI is unset. Using the in-memory store. Set MONGODB_URI to use Atlas Vector Search.");
    return new InMemoryVectorStore();
  }

  private static void requireQuery(String[] args) {
    if (args.length < 2 || args[1].isBlank()) {
      throw new IllegalArgumentException("Provide the query text");
    }
  }
}
