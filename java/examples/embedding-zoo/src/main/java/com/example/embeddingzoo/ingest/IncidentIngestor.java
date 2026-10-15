package com.example.embeddingzoo.ingest;

import com.example.embeddingzoo.Datasets;
import com.example.embeddingzoo.Vectors;
import com.example.embeddingzoo.ZooConfig;
import com.example.embeddingzoo.embed.TextEmbedder;
import com.example.embeddingzoo.store.StoredRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.agentengine.sdk.Json;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fetches public Netflix Technology Blog postmortems.
 *
 * <p>Article text is cached under {@code data/cache} after a successful fetch. A blocked or empty
 * page is skipped. Nothing is written from a title alone.
 */
public final class IncidentIngestor {

  private IncidentIngestor() {}

  public static List<StoredRecord> load(ZooConfig config, TextEmbedder text) throws IOException {
    JsonNode catalog;
    try (InputStream in = IncidentIngestor.class.getResourceAsStream("/incidents/catalog.json")) {
      if (in == null) {
        throw new IOException("Missing incidents/catalog.json");
      }
      catalog = Json.mapper().readTree(in);
    }
    List<StoredRecord> records = new ArrayList<>();
    for (JsonNode item : catalog) {
      String title = item.path("title").asText();
      String canonical = item.path("canonical_url").asText();
      String incidentDate = item.path("incident_date").asText();
      String textBody = fetch(config, canonical);
      if (textBody == null) {
        System.err.println("Skipped incident, page not retrieved: " + canonical);
        continue;
      }
      String articleHash = Vectors.sha256(textBody);
      List<String> chunks = HtmlText.chunks(textBody, 120, 30, 8);
      for (int i = 0; i < chunks.size(); i++) {
        String body = title + ". " + chunks.get(i);
        String locator = canonical + "#chunk-" + i;
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("document_url", canonical);
        metadata.put("title", title);
        metadata.put("incident_date", incidentDate);
        metadata.put("chunk", Integer.toString(i));
        metadata.put("chunk_count", Integer.toString(chunks.size()));
        metadata.put("publisher", Datasets.INCIDENT_PUBLISHER);
        metadata.put("outlet", "Netflix Technology Blog");
        records.add(record(text, locator, body, articleHash, metadata, canonical, title));
      }
      System.err.println("Ingested incident " + title + " (" + chunks.size() + " chunks)");
    }
    return records;
  }

  private static StoredRecord record(
      TextEmbedder text, String locator, String body, String articleHash, Map<String, String> metadata,
      String canonical, String title) {
    Map<String, String> provenance = new LinkedHashMap<>();
    provenance.put("dataset_name", Datasets.INCIDENT_NAME);
    provenance.put("source_url", canonical);
    provenance.put("license", Datasets.INCIDENT_LICENSE);
    provenance.put("citation", title);
    provenance.put("retrieved_at", Instant.now().toString());
    provenance.put("content_sha256", articleHash);
    provenance.put("locator", locator);
    provenance.put("publisher_checksum_verified", "false");
    provenance.put("placed_by_user", "false");
    return StoredRecord.builder()
        .id(Vectors.idFor(Datasets.INCIDENT_ID, locator, text.modelId()))
        .modality("incident")
        .datasetId(Datasets.INCIDENT_ID)
        .text(body)
        .textEmbedding(text.embed(body))
        .textEmbeddingModel(text.modelId())
        .textEmbeddingArtifact(text.artifact())
        .metadata(metadata)
        .provenance(provenance)
        .build();
  }

  private static String fetch(ZooConfig config, String canonical) throws IOException {
    Path cache = config.dataDir().resolve("cache").resolve("incidents").resolve(Vectors.sha256(canonical) + ".txt");
    if (Files.isRegularFile(cache)) {
      return Files.readString(cache);
    }
    String text = pageText(URI.create(canonical));
    if (text == null) {
      text = pageText(archive(canonical));
    }
    if (text == null || text.length() < 400) {
      return null;
    }
    Files.createDirectories(cache.getParent());
    Files.writeString(cache, text);
    return text;
  }

  private static String pageText(URI uri) {
    if (uri == null) {
      return null;
    }
    try {
      String html = HttpTexts.get(uri);
      if (HttpTexts.blocked(html)) {
        return null;
      }
      String text = HtmlText.visibleText(html);
      return text.length() < 400 ? null : text;
    } catch (IOException e) {
      System.err.println("Fetch failed for " + uri + ": " + e.getMessage());
      return null;
    }
  }

  private static URI archive(String canonical) {
    try {
      String cdx = "https://web.archive.org/cdx/search/cdx?url="
          + java.net.URLEncoder.encode(canonical, java.nio.charset.StandardCharsets.UTF_8)
          + "&output=json&filter=statuscode:200&fl=timestamp,original&limit=1&sort=reverse";
      String body = HttpTexts.get(URI.create(cdx));
      var rows = Json.mapper().readTree(body);
      if (!rows.isArray() || rows.size() < 2) {
        return null;
      }
      String timestamp = rows.get(1).get(0).asText();
      String original = rows.get(1).get(1).asText();
      return URI.create("https://web.archive.org/web/" + timestamp + "/" + original);
    } catch (IOException | RuntimeException e) {
      System.err.println("Archive lookup failed for " + canonical + ": " + e.getMessage());
      return null;
    }
  }
}
