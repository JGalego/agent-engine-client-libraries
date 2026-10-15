package com.example.embeddingzoo.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mongodb.agentengine.sdk.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Hashes and bibliographic fields for one downloaded or user-placed corpus. */
public final class CorpusManifest {

  public final String datasetId;
  public final String datasetName;
  public final String sourceUrl;
  public final String downloadUrl;
  public final String license;
  public final String licenseUrl;
  public final String citation;
  public final String publisher;
  public final boolean publisherChecksumVerified;
  public final boolean placedByUser;
  public final String retrievedAt;
  public final List<FileEntry> files;

  public CorpusManifest(
      String datasetId,
      String datasetName,
      String sourceUrl,
      String downloadUrl,
      String license,
      String licenseUrl,
      String citation,
      String publisher,
      boolean publisherChecksumVerified,
      boolean placedByUser,
      String retrievedAt,
      List<FileEntry> files) {
    this.datasetId = datasetId;
    this.datasetName = datasetName;
    this.sourceUrl = sourceUrl;
    this.downloadUrl = downloadUrl;
    this.license = license;
    this.licenseUrl = licenseUrl;
    this.citation = citation;
    this.publisher = publisher;
    this.publisherChecksumVerified = publisherChecksumVerified;
    this.placedByUser = placedByUser;
    this.retrievedAt = retrievedAt;
    this.files = List.copyOf(files);
  }

  public void write(Path path) throws IOException {
    Files.createDirectories(path.getParent());
    ObjectNode node = Json.mapper().createObjectNode();
    node.put("dataset_id", datasetId);
    node.put("dataset_name", datasetName);
    node.put("source_url", sourceUrl);
    node.put("download_url", downloadUrl);
    node.put("license", license);
    node.put("license_url", licenseUrl);
    node.put("citation", citation);
    node.put("publisher", publisher);
    node.put("publisher_checksum_verified", publisherChecksumVerified);
    node.put("placed_by_user", placedByUser);
    node.put("retrieved_at", retrievedAt);
    ArrayNode array = node.putArray("files");
    for (FileEntry file : files) {
      ObjectNode item = array.addObject();
      item.put("path", file.path());
      item.put("sha256", file.sha256());
      item.put("bytes", file.bytes());
    }
    Json.mapper().writerWithDefaultPrettyPrinter().writeValue(path.toFile(), node);
  }

  public static CorpusManifest read(Path path) throws IOException {
    JsonNode node = Json.mapper().readTree(path.toFile());
    List<FileEntry> files = new ArrayList<>();
    for (JsonNode item : node.path("files")) {
      files.add(new FileEntry(item.path("path").asText(), item.path("sha256").asText(), item.path("bytes").asLong()));
    }
    return new CorpusManifest(
        node.path("dataset_id").asText(),
        node.path("dataset_name").asText(),
        node.path("source_url").asText(),
        node.path("download_url").asText(),
        node.path("license").asText(),
        node.path("license_url").asText(),
        node.path("citation").asText(),
        node.path("publisher").asText(),
        node.path("publisher_checksum_verified").asBoolean(false),
        node.path("placed_by_user").asBoolean(false),
        node.path("retrieved_at").asText(),
        files);
  }

  public FileEntry require(String relativePath) {
    for (FileEntry file : files) {
      if (file.path().equals(relativePath)) {
        return file;
      }
    }
    return null;
  }

  public record FileEntry(String path, String sha256, long bytes) {}
}
