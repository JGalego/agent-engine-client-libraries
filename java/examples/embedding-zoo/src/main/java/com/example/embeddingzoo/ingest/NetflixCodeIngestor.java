package com.example.embeddingzoo.ingest;

import com.example.embeddingzoo.Datasets;
import com.example.embeddingzoo.Vectors;
import com.example.embeddingzoo.ZooConfig;
import com.example.embeddingzoo.embed.TextEmbedder;
import com.example.embeddingzoo.store.StoredRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.agentengine.sdk.Json;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ingests READMEs from public Netflix repositories.
 *
 * <p>A repository is skipped when the GitHub API does not report an SPDX license. The license
 * identifier that GitHub returns is stored on the record.
 */
public final class NetflixCodeIngestor {

  private static final List<String> REPOS = List.of(
      "Netflix/chaosmonkey",
      "Netflix/zuul",
      "Netflix/Hystrix",
      "Netflix/eureka",
      "Netflix/conductor");

  private NetflixCodeIngestor() {}

  public static List<StoredRecord> load(ZooConfig config, TextEmbedder embedder) {
    List<StoredRecord> records = new ArrayList<>();
    for (String repo : REPOS) {
      try {
        records.addAll(one(config, embedder, repo));
      } catch (IOException e) {
        System.err.println("Skipped " + repo + ": " + e.getMessage());
      }
    }
    return records;
  }

  private static List<StoredRecord> one(ZooConfig config, TextEmbedder embedder, String repo) throws IOException {
    Path cache = config.dataDir().resolve("cache").resolve("code").resolve(repo.replace('/', '_') + ".json");
    JsonNode meta;
    String readme;
    if (Files.isRegularFile(cache)) {
      JsonNode cached = Json.mapper().readTree(cache.toFile());
      meta = cached.path("meta");
      readme = cached.path("readme").asText();
    } else {
      meta = Json.mapper().readTree(HttpTexts.get(URI.create("https://api.github.com/repos/" + repo)));
      String branch = meta.path("default_branch").asText("master");
      readme = HttpTexts.get(URI.create("https://raw.githubusercontent.com/" + repo + "/" + branch + "/README.md"));
      Files.createDirectories(cache.getParent());
      var node = Json.mapper().createObjectNode();
      node.set("meta", meta);
      node.put("readme", readme);
      Json.mapper().writerWithDefaultPrettyPrinter().writeValue(cache.toFile(), node);
    }
    String spdx = meta.path("license").path("spdx_id").asText("");
    if (spdx.isBlank() || "NOASSERTION".equals(spdx)) {
      throw new IOException("GitHub did not report an SPDX license for " + repo);
    }
    String htmlUrl = meta.path("html_url").asText("https://github.com/" + repo);
    String licenseUrl = meta.path("license").path("url").asText("");
    List<String> chunks = HtmlText.chunks(readme, 120, 30, 6);
    String hash = Vectors.sha256(readme);
    List<StoredRecord> records = new ArrayList<>();
    for (int i = 0; i < chunks.size(); i++) {
      String body = repo + " README. " + chunks.get(i);
      String locator = htmlUrl + "#chunk-" + i;
      Map<String, String> metadata = new LinkedHashMap<>();
      metadata.put("document_url", htmlUrl);
      metadata.put("repository", repo);
      metadata.put("spdx", spdx);
      metadata.put("chunk", Integer.toString(i));
      metadata.put("publisher", Datasets.CODE_PUBLISHER);
      metadata.put("outlet", "GitHub");
      Map<String, String> provenance = new LinkedHashMap<>();
      provenance.put("dataset_name", Datasets.CODE_NAME);
      provenance.put("source_url", htmlUrl);
      provenance.put("license", spdx);
      provenance.put("license_url", licenseUrl);
      provenance.put("citation", repo + " README");
      provenance.put("retrieved_at", Instant.now().toString());
      provenance.put("content_sha256", hash);
      provenance.put("locator", locator);
      provenance.put("publisher_checksum_verified", "false");
      provenance.put("placed_by_user", "false");
      records.add(StoredRecord.builder()
          .id(Vectors.idFor(Datasets.CODE_ID, locator, embedder.modelId()))
          .modality("code")
          .datasetId(Datasets.CODE_ID)
          .text(body)
          .textEmbedding(embedder.embed(body))
          .textEmbeddingModel(embedder.modelId())
          .textEmbeddingArtifact(embedder.artifact())
          .metadata(metadata)
          .provenance(provenance)
          .build());
    }
    System.err.println("Ingested README " + repo + " (" + spdx + ", " + chunks.size() + " chunks)");
    return records;
  }
}
