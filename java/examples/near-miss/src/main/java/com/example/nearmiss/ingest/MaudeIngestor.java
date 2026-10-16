package com.example.nearmiss.ingest;

import com.example.nearmiss.Datasets;
import com.example.nearmiss.Vectors;
import com.example.nearmiss.NearMissConfig;
import com.example.nearmiss.embed.TextEmbedder;
import com.example.nearmiss.store.StoredRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.agentengine.sdk.Json;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fetches recent death and injury reports from the FDA's MAUDE database, through openFDA.
 *
 * <p>Only the opening of the event narrative and the device fields are kept. Patient fields are never read. Each
 * response is cached under {@code data/cache/maude}, so a re-run does not call the API again.
 */
public final class MaudeIngestor {

  /** Device types that give the demo reports about different kinds of failure. */
  static final List<String> DEVICES = List.of(
      "infusion pump", "insulin pump", "pacemaker", "defibrillator", "ventilator", "hip", "catheter", "glucose");

  private static final int MIN_NARRATIVE_CHARS = 80;

  private MaudeIngestor() {}

  public static List<StoredRecord> load(NearMissConfig config, TextEmbedder text) throws IOException {
    List<StoredRecord> records = new ArrayList<>();
    for (String device : DEVICES) {
      URI uri = query(device, config.recordsPerQuery());
      JsonNode results = fetch(config, uri).path("results");
      int reports = 0;
      for (JsonNode report : results) {
        reports += add(report, text, records);
      }
      System.err.println("MAUDE " + device + ": " + reports + " reports");
    }
    return records;
  }

  static URI query(String device, int limit) {
    String search = "device.generic_name:\"" + device + "\" AND _exists_:mdr_text"
        + " AND (event_type:\"Death\" OR event_type:\"Injury\") AND date_received:[20200101 TO 20261231]";
    return URI.create(Datasets.MAUDE_API + "?search=" + URLEncoder.encode(search, StandardCharsets.UTF_8)
        + "&sort=date_received:desc&limit=" + limit);
  }

  /** Adds the chunks of one report. Returns 1 when the report had a usable narrative. */
  static int add(JsonNode report, TextEmbedder text, List<StoredRecord> out) {
    String narrative = narrative(report);
    if (narrative.length() < MIN_NARRATIVE_CHARS) {
      return 0;
    }
    String reportNumber = report.path("report_number").asText();
    JsonNode device = report.path("device").path(0);
    String deviceName = device.path("generic_name").asText("");
    String brand = device.path("brand_name").asText("");
    String url = Datasets.MAUDE_API + "?search=report_number:%22" + reportNumber + "%22";
    // Later in a long narrative the manufacturer starts to repeat its boilerplate, so only the opening counts.
    List<String> chunks = TextChunks.split(narrative, 120, 30, 1);
    for (int i = 0; i < chunks.size(); i++) {
      String body = deviceName + ", " + report.path("event_type").asText() + ". " + chunks.get(i);
      String locator = reportNumber + "#chunk-" + i;
      Map<String, String> metadata = new LinkedHashMap<>();
      metadata.put("document_url", url);
      metadata.put("report_number", reportNumber);
      metadata.put("event_type", report.path("event_type").asText());
      metadata.put("date_received", report.path("date_received").asText());
      metadata.put("device", deviceName);
      metadata.put("brand_name", brand);
      metadata.put("manufacturer", device.path("manufacturer_d_name").asText(""));
      metadata.put("product_code", device.path("device_report_product_code").asText(""));
      metadata.put("chunk", Integer.toString(i));
      metadata.put("chunk_count", Integer.toString(chunks.size()));
      metadata.put("publisher", Datasets.MAUDE_PUBLISHER);
      out.add(StoredRecord.builder()
          .id(Vectors.idFor(Datasets.MAUDE_ID, locator, text.modelId()))
          .kind(Datasets.KIND_DEVICE)
          .datasetId(Datasets.MAUDE_ID)
          .text(body)
          .embedding(text.embed(body))
          .embeddingModel(text.modelId())
          .embeddingArtifact(text.artifact())
          .metadata(metadata)
          .provenance(Provenance.of(
              Datasets.MAUDE_NAME, url, Datasets.MAUDE_LICENSE, Datasets.MAUDE_LICENSE_URL,
              Datasets.MAUDE_CITATION, locator, Vectors.sha256(narrative)))
          .build());
    }
    return 1;
  }

  /** The "Description of Event or Problem" text of the report, or the first text block if it has none. */
  static String narrative(JsonNode report) {
    String first = "";
    for (JsonNode block : report.path("mdr_text")) {
      String value = block.path("text").asText("").trim();
      if (first.isEmpty()) {
        first = value;
      }
      if ("Description of Event or Problem".equals(block.path("text_type_code").asText())) {
        return value;
      }
    }
    return first;
  }

  private static JsonNode fetch(NearMissConfig config, URI uri) throws IOException {
    Path cache = config.dataDir().resolve("cache").resolve("maude")
        .resolve(Vectors.sha256(uri.toString()).substring(0, 16) + ".json");
    if (Files.isRegularFile(cache)) {
      return Json.mapper().readTree(cache.toFile());
    }
    String body = HttpTexts.get(uri);
    Files.createDirectories(cache.getParent());
    Files.writeString(cache, body);
    return Json.mapper().readTree(body);
  }
}
