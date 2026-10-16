package com.example.nearmiss;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Map;

/** Environment and filesystem locations for the demo. */
public final class NearMissConfig {

  private final Path home;
  private final Path dataDir;
  private final String mongoUri;
  private final String mongoDatabase;
  private final String mongoCollection;
  private final String textProvider;
  private final String openAiKey;
  private final String openAiBaseUrl;
  private final String openAiEmbeddingModel;
  private final int recordsPerQuery;
  private final double analogyCutoff;

  private NearMissConfig(
      Path home,
      Path dataDir,
      String mongoUri,
      String mongoDatabase,
      String mongoCollection,
      String textProvider,
      String openAiKey,
      String openAiBaseUrl,
      String openAiEmbeddingModel,
      int recordsPerQuery,
      double analogyCutoff) {
    this.home = home;
    this.dataDir = dataDir;
    this.mongoUri = mongoUri;
    this.mongoDatabase = mongoDatabase;
    this.mongoCollection = mongoCollection;
    this.textProvider = textProvider;
    this.openAiKey = openAiKey;
    this.openAiBaseUrl = openAiBaseUrl;
    this.openAiEmbeddingModel = openAiEmbeddingModel;
    this.recordsPerQuery = recordsPerQuery;
    this.analogyCutoff = analogyCutoff;
  }

  public static NearMissConfig load() {
    try {
      Path home = locateHome();
      Map<String, String> env = EnvFiles.merged(home.resolve(".env"));
      String data = first(System.getProperty("near.miss.data"), env.get("NEAR_MISS_DATA"));
      Path dataDir = data == null ? home.resolve("data") : Path.of(data);
      String uri = blankToNull(env.get("MONGODB_URI"));
      return new NearMissConfig(
          home,
          dataDir,
          uri,
          env.getOrDefault("MONGODB_DATABASE", "near_miss"),
          env.getOrDefault("MONGODB_COLLECTION", "evidence"),
          env.getOrDefault("NEAR_MISS_TEXT_PROVIDER", "minilm"),
          blankToNull(env.get("OPENAI_API_KEY")),
          env.getOrDefault("OPENAI_BASE_URL", "https://api.openai.com/v1"),
          env.getOrDefault("OPENAI_EMBEDDING_MODEL", "text-embedding-3-small"),
          parseInt(env.get("NEAR_MISS_RECORDS_PER_QUERY"), 25),
          parseDouble(env.get("NEAR_MISS_ANALOGY_CUTOFF"), 0.45));
    } catch (IOException e) {
      throw new IllegalStateException("Could not read near-miss configuration", e);
    }
  }

  public Path home() { return home; }
  public Path dataDir() { return dataDir; }
  public String mongoUri() { return mongoUri; }
  public String mongoDatabase() { return mongoDatabase; }
  public String mongoCollection() { return mongoCollection; }
  public String textProvider() { return textProvider; }
  public String openAiKey() { return openAiKey; }
  public String openAiBaseUrl() { return openAiBaseUrl; }
  public String openAiEmbeddingModel() { return openAiEmbeddingModel; }
  public int recordsPerQuery() { return recordsPerQuery; }
  public double analogyCutoff() { return analogyCutoff; }
  public Path modelDir() { return dataDir.resolve("models").resolve("minilm"); }
  public boolean atlasConfigured() { return mongoUri != null; }

  static Path locateHome() {
    String configured = first(System.getProperty("near.miss.home"), System.getenv("NEAR_MISS_HOME"));
    if (configured != null && !configured.isBlank()) {
      return Path.of(configured);
    }
    try {
      Path location = Path.of(NearMissConfig.class.getProtectionDomain().getCodeSource().getLocation().toURI());
      if (location.endsWith(Path.of("target", "classes"))) {
        return location.getParent().getParent();
      }
      Path parent = location.getParent();
      if (parent != null && "lib".equals(String.valueOf(parent.getFileName()))) {
        return parent.getParent();
      }
    } catch (URISyntaxException | RuntimeException ignored) {
      // Fall through to the working directory.
    }
    return Path.of("").toAbsolutePath();
  }

  private static String first(String preferred, String fallback) {
    if (preferred != null && !preferred.isBlank()) {
      return preferred;
    }
    return fallback;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static int parseInt(String value, int fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    return Integer.parseInt(value.trim());
  }

  private static double parseDouble(String value, double fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    return Double.parseDouble(value.trim());
  }
}
