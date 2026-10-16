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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fetches owner complaints from NHTSA for a few vehicles.
 *
 * <p>Complaints that report a crash, a fire, an injury or a death come first. The VIN fragment the API
 * returns is not stored. Each response is cached under {@code data/cache/nhtsa}.
 */
public final class NhtsaIngestor {

  record Vehicle(String make, String model, int year) {
    String label() {
      return make + " " + model + " " + year;
    }
  }

  static final List<Vehicle> VEHICLES = List.of(
      new Vehicle("TESLA", "MODEL 3", 2020),
      new Vehicle("TOYOTA", "PRIUS", 2012),
      new Vehicle("HONDA", "ACCORD", 2018),
      new Vehicle("CHEVROLET", "BOLT EV", 2019),
      new Vehicle("JEEP", "GRAND CHEROKEE", 2016),
      new Vehicle("FORD", "F-150", 2018));

  private static final int MIN_SUMMARY_CHARS = 80;

  private NhtsaIngestor() {}

  public static List<StoredRecord> load(NearMissConfig config, TextEmbedder text) throws IOException {
    List<StoredRecord> records = new ArrayList<>();
    for (Vehicle vehicle : VEHICLES) {
      URI uri = query(vehicle);
      List<JsonNode> complaints = new ArrayList<>();
      fetch(config, uri).path("results").forEach(complaints::add);
      complaints.sort(Comparator.comparingInt(NhtsaIngestor::severity).reversed());
      int added = 0;
      for (JsonNode complaint : complaints) {
        if (added == config.recordsPerQuery()) {
          break;
        }
        added += add(vehicle, complaint, uri, text, records);
      }
      System.err.println("NHTSA " + vehicle.label() + ": " + added + " complaints");
    }
    return records;
  }

  static URI query(Vehicle vehicle) {
    return URI.create(Datasets.NHTSA_API
        + "?make=" + URLEncoder.encode(vehicle.make(), StandardCharsets.UTF_8)
        + "&model=" + URLEncoder.encode(vehicle.model(), StandardCharsets.UTF_8)
        + "&modelYear=" + vehicle.year());
  }

  /** Crashes, fires, injuries and deaths sort ahead of the rest. */
  static int severity(JsonNode complaint) {
    return (complaint.path("crash").asBoolean() ? 1 : 0)
        + (complaint.path("fire").asBoolean() ? 1 : 0)
        + complaint.path("numberOfInjuries").asInt()
        + 5 * complaint.path("numberOfDeaths").asInt();
  }

  /** Adds the chunks of one complaint. Returns 1 when the complaint had a usable summary. */
  static int add(Vehicle vehicle, JsonNode complaint, URI source, TextEmbedder text, List<StoredRecord> out) {
    String summary = complaint.path("summary").asText("").trim();
    if (summary.length() < MIN_SUMMARY_CHARS) {
      return 0;
    }
    String odi = complaint.path("odiNumber").asText();
    String components = complaint.path("components").asText("");
    List<String> chunks = TextChunks.split(summary, 120, 30, 3);
    for (int i = 0; i < chunks.size(); i++) {
      String body = vehicle.label() + ", " + components + ". " + chunks.get(i);
      String locator = "ODI-" + odi + "#chunk-" + i;
      Map<String, String> metadata = new LinkedHashMap<>();
      metadata.put("document_url", source + "#ODI-" + odi);
      metadata.put("odi_number", odi);
      metadata.put("vehicle", vehicle.label());
      metadata.put("components", components);
      metadata.put("crash", complaint.path("crash").asText());
      metadata.put("fire", complaint.path("fire").asText());
      metadata.put("injuries", complaint.path("numberOfInjuries").asText());
      metadata.put("deaths", complaint.path("numberOfDeaths").asText());
      metadata.put("date_filed", complaint.path("dateComplaintFiled").asText());
      metadata.put("chunk", Integer.toString(i));
      metadata.put("chunk_count", Integer.toString(chunks.size()));
      metadata.put("publisher", Datasets.NHTSA_PUBLISHER);
      out.add(StoredRecord.builder()
          .id(Vectors.idFor(Datasets.NHTSA_ID, locator, text.modelId()))
          .kind(Datasets.KIND_VEHICLE)
          .datasetId(Datasets.NHTSA_ID)
          .text(body)
          .embedding(text.embed(body))
          .embeddingModel(text.modelId())
          .embeddingArtifact(text.artifact())
          .metadata(metadata)
          .provenance(Provenance.of(
              Datasets.NHTSA_NAME, source.toString(), Datasets.NHTSA_LICENSE, Datasets.NHTSA_LICENSE_URL,
              Datasets.NHTSA_CITATION, locator, Vectors.sha256(summary)))
          .build());
    }
    return 1;
  }

  private static JsonNode fetch(NearMissConfig config, URI uri) throws IOException {
    Path cache = config.dataDir().resolve("cache").resolve("nhtsa")
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
