package com.example.embeddingzoo;

import com.example.embeddingzoo.dashboard.ImsDashboard;
import com.example.embeddingzoo.embed.TextEmbedder;
import com.example.embeddingzoo.embed.TextEmbedders;
import com.example.embeddingzoo.ingest.CorpusManifest;
import com.example.embeddingzoo.ingest.HttpTexts;
import com.example.embeddingzoo.ingest.ImsAcquisition;
import com.example.embeddingzoo.ingest.IncidentIngestor;
import com.example.embeddingzoo.ingest.LogHubIngestor;
import com.example.embeddingzoo.ingest.MvtecIngestor;
import com.example.embeddingzoo.ingest.NetflixCodeIngestor;
import com.example.embeddingzoo.ingest.TelemetryIngestor;
import com.example.embeddingzoo.store.AtlasVectorStore;
import com.example.embeddingzoo.store.InMemoryVectorStore;
import com.example.embeddingzoo.store.StoredRecord;
import com.example.embeddingzoo.store.VectorStore;
import com.mongodb.agentengine.sdk.Json;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Command line for download, ingest, dashboards, and retrieval. */
public final class ZooMain {

  private ZooMain() {}

  public static void main(String[] args) throws Exception {
    System.setProperty("java.awt.headless", "true");
    if (args.length == 0) {
      System.err.println("Usage: download | dashboard | ingest | search <query> | correlate <query> | demo | accept-mvtec");
      System.exit(2);
      return;
    }
    ZooConfig config = ZooConfig.load();
    switch (args[0]) {
      case "download" -> download(config);
      case "dashboard" -> dashboard(config);
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
      case "accept-mvtec" -> MvtecIngestor.acceptUserDirectory(config, MvtecIngestor.root(config));
      case "demo" -> demo(config);
      default -> throw new IllegalArgumentException("Unknown command " + args[0]);
    }
  }

  private static void download(ZooConfig config) throws IOException {
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
    LogHubIngestor.ensure(config);
    ImsAcquisition.ensure(config);
    System.err.println("Download finished. MVTec AD is not fetched automatically (CC BY-NC-SA 4.0, large archive).");
    System.err.println("Place files in " + MvtecIngestor.root(config) + " and run accept-mvtec.");
  }

  private static void dashboard(ZooConfig config) throws IOException {
    CorpusManifest manifest = ImsAcquisition.ensure(config);
    List<Path> images = ImsDashboard.render(config, manifest);
    for (Path image : images) {
      System.out.println(image);
    }
  }

  private static void ingest(ZooConfig config) throws IOException {
    try (ZooToolkit toolkit = prepared(config, true)) {
      System.err.println(config.atlasConfigured()
          ? "Records are in Atlas."
          : "The in-memory store is discarded when this process exits. Run demo to search in one process, or set MONGODB_URI.");
      System.out.println(Json.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
          "text_model", toolkit.embedder().modelId(),
          "store", config.atlasConfigured() ? "atlas" : "in_memory")));
    }
  }

  private static void demo(ZooConfig config) throws Exception {
    List<Path> images = List.of();
    try {
      images = ImsDashboard.render(config, ImsAcquisition.ensure(config));
    } catch (IOException e) {
      System.err.println("Dashboard skipped: " + e.getMessage());
    }
    try (ZooToolkit toolkit = prepared(config, true)) {
      Map<String, Object> out = new LinkedHashMap<>();
      out.put("dashboards", images.stream().map(Path::toString).toList());
      out.put("bearing_search", toolkit.semanticSearch(Map.of("query", "bearing vibration with high kurtosis", "limit", 3)));
      out.put("incident_search", toolkit.semanticSearch(Map.of("query", "load balancer outage on Christmas Eve", "limit", 3)));
      out.put("correlation", toolkit.correlate(Map.of("query", "packet responder terminating an HDFS block")));
      System.out.println(Json.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(out));
    }
  }

  private static ZooToolkit open(ZooConfig config) throws IOException {
    if (config.atlasConfigured()) {
      return prepared(config, false);
    }
    return prepared(config, true);
  }

  private static ZooToolkit prepared(ZooConfig config, boolean ingest) throws IOException {
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
        boolean ready = atlas.awaitTextIndex(AtlasVectorStore.DurationLike.seconds(90));
        System.err.println(ready
            ? "Atlas text index is READY."
            : "Atlas text index is not READY yet. Searches use an exact cosine scan until it is, and say so.");
      }
    }
    return new ZooToolkit(config, store, embedder);
  }

  private static List<StoredRecord> collect(ZooConfig config, TextEmbedder embedder) throws IOException {
    List<StoredRecord> records = new ArrayList<>();
    records.addAll(TelemetryIngestor.load(config, CorpusManifest.read(ImsAcquisition.manifestPath(config)), embedder));
    records.addAll(LogHubIngestor.load(config, LogHubIngestor.ensure(config), embedder));
    records.addAll(IncidentIngestor.load(config, embedder));
    records.addAll(NetflixCodeIngestor.load(config, embedder));
    records.addAll(MvtecIngestor.load(config, embedder));
    return records;
  }

  private static VectorStore openStore(ZooConfig config) {
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
