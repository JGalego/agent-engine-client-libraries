package com.example.embeddingzoo.ingest;

import com.example.embeddingzoo.Datasets;
import com.example.embeddingzoo.Samples;
import com.example.embeddingzoo.Vectors;
import com.example.embeddingzoo.ZooConfig;
import com.example.embeddingzoo.embed.TextEmbedder;
import com.example.embeddingzoo.signal.ImsLayout;
import com.example.embeddingzoo.signal.ImsSignalFile;
import com.example.embeddingzoo.signal.VibrationFeatures;
import com.example.embeddingzoo.store.StoredRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.agentengine.sdk.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Embeds a documented sample of IMS channels. The dashboard still plots every extracted snapshot. */
public final class TelemetryIngestor {

  private TelemetryIngestor() {}

  public static List<StoredRecord> load(ZooConfig config, CorpusManifest manifest, TextEmbedder text) throws IOException {
    Path root = ImsAcquisition.snapshots(config);
    List<Path> files = new ArrayList<>();
    for (CorpusManifest.FileEntry entry : manifest.files) {
      files.add(root.resolve(entry.path()));
    }
    List<Path> selected = Samples.select(files, config.maxSnapshots(), true);
    Map<String, String> datasheet = datasheet(config);
    List<StoredRecord> records = new ArrayList<>();
    int done = 0;
    for (Path file : selected) {
      String relative = root.relativize(file).toString().replace('\\', '/');
      CorpusManifest.FileEntry entry = manifest.require(relative);
      String actual = Vectors.sha256(file);
      if (entry == null || !entry.sha256().equals(actual)) {
        throw new IOException("IMS file hash does not match the manifest: " + relative);
      }
      double[][] channels = ImsSignalFile.readChannels(file);
      String timestamp = ImsSignalFile.timestampLabel(file.getFileName().toString()).orElse("");
      for (int channel = 0; channel < channels.length; channel++) {
        VibrationFeatures.Summary summary = VibrationFeatures.summarize(channels[channel]);
        int bearing = ImsLayout.bearing(channels.length, channel);
        String test = ImsLayout.testSet(relative, channels.length);
        String endNote = ImsLayout.testEndNote(relative, channels.length, bearing);
        String card = card(timestamp, test, bearing, channel + 1, summary);
        String locator = relative + "#ch" + (channel + 1);
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("recording_id", relative);
        metadata.put("timestamp_label", timestamp);
        metadata.put("test_set", test);
        metadata.put("bearing", Integer.toString(bearing));
        metadata.put("channel", Integer.toString(channel + 1));
        metadata.put("sample_count", Integer.toString(summary.sampleCount()));
        metadata.put("documented_sample_rate_hz", "20000");
        metadata.put("rms", format(summary.rms()));
        metadata.put("standardized_kurtosis", format(summary.standardizedKurtosis()));
        metadata.put("crest_factor", format(summary.crestFactor()));
        metadata.put("spectral_centroid_hz", format(summary.spectralCentroidHz()));
        metadata.put("publisher", manifest.publisher);
        if (!endNote.isEmpty()) {
          metadata.put("documented_test_end", endNote);
          metadata.put("label_scope", "dataset documentation for the whole test, not a label of this snapshot");
        }
        datasheet.forEach(metadata::putIfAbsent);
        records.add(StoredRecord.builder()
            .id(Vectors.idFor(manifest.datasetId, locator, text.modelId()))
            .modality("telemetry")
            .datasetId(manifest.datasetId)
            .text(card)
            .textEmbedding(text.embed(card))
            .textEmbeddingModel(text.modelId())
            .textEmbeddingArtifact(text.artifact())
            .vibrationEmbedding(summary.embedding())
            .vibrationEmbeddingModel(Datasets.VIBRATION_MODEL)
            .metadata(metadata)
            .provenance(provenance(manifest, locator, actual))
            .build());
      }
      done++;
      if (done % 5 == 0) {
        System.err.println("Embedded " + done + " of " + selected.size() + " IMS snapshots");
      }
    }
    return records;
  }

  private static String card(String timestamp, String test, int bearing, int channel, VibrationFeatures.Summary summary) {
    return String.format(Locale.ROOT,
        "NASA IMS bearings vibration snapshot %s test %s bearing %d channel %d. "
            + "Recorded RMS %.5f, mean %.5f, population standard deviation %.5f, standardized kurtosis %.3f, "
            + "skewness %.3f, crest factor %.3f, spectral centroid %.1f Hz. Sample count %d. "
            + "Amplitude uses the units stored in the file. The documented sample rate is 20000 Hz.",
        timestamp.isEmpty() ? "unknown-time" : timestamp,
        test, bearing, channel,
        summary.rms(), summary.mean(), summary.standardDeviation(), summary.standardizedKurtosis(),
        summary.skewness(), summary.crestFactor(), summary.spectralCentroidHz(), summary.sampleCount());
  }

  private static Map<String, String> datasheet(ZooConfig config) throws IOException {
    Path facts = config.dataDir().resolve("ims").resolve("datasheet-facts.json");
    if (!Files.isRegularFile(facts)) {
      return Map.of();
    }
    JsonNode node = Json.mapper().readTree(facts.toFile());
    Map<String, String> out = new LinkedHashMap<>();
    if (node.has("shaft_speed_rpm")) {
      out.put("documented_shaft_speed_rpm", node.get("shaft_speed_rpm").asText());
    }
    if (node.has("radial_load_lbs")) {
      out.put("documented_radial_load_lbs", node.get("radial_load_lbs").asText());
    }
    return out;
  }

  static Map<String, String> provenance(CorpusManifest manifest, String locator, String sha) {
    Map<String, String> map = new LinkedHashMap<>();
    map.put("dataset_name", manifest.datasetName);
    map.put("source_url", manifest.sourceUrl);
    map.put("download_url", manifest.downloadUrl);
    map.put("license", manifest.license);
    map.put("license_url", manifest.licenseUrl);
    map.put("citation", manifest.citation);
    map.put("retrieved_at", Instant.now().toString());
    map.put("content_sha256", sha);
    map.put("locator", locator);
    map.put("publisher_checksum_verified", Boolean.toString(manifest.publisherChecksumVerified));
    map.put("placed_by_user", Boolean.toString(manifest.placedByUser));
    return map;
  }

  private static String format(double value) {
    return String.format(Locale.ROOT, "%.5f", value);
  }
}
