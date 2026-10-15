package com.example.embeddingzoo.ingest;

import com.example.embeddingzoo.Datasets;
import com.example.embeddingzoo.Samples;
import com.example.embeddingzoo.Vectors;
import com.example.embeddingzoo.ZooConfig;
import com.example.embeddingzoo.embed.TextEmbedder;
import com.example.embeddingzoo.store.StoredRecord;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Ingests the 2,000-line HDFS sample published in the Loghub repository. */
public final class LogHubIngestor {

  private LogHubIngestor() {}

  public static CorpusManifest ensure(ZooConfig config) throws IOException {
    Path manifestFile = config.dataDir().resolve("logs").resolve("manifest.json");
    if (Files.isRegularFile(manifestFile)) {
      return CorpusManifest.read(manifestFile);
    }
    Path log = config.dataDir().resolve("logs").resolve("HDFS_2k.log");
    if (!Files.isRegularFile(log)) {
      HttpTexts.download(URI.create(Datasets.LOG_DOWNLOAD), log);
    }
    CorpusManifest manifest = new CorpusManifest(
        Datasets.LOG_ID,
        Datasets.LOG_NAME,
        Datasets.LOG_PAGE,
        Datasets.LOG_DOWNLOAD,
        Datasets.LOG_LICENSE,
        Datasets.LOG_LICENSE_URL,
        Datasets.LOG_CITATION,
        Datasets.LOG_PUBLISHER,
        false,
        false,
        Instant.now().toString(),
        List.of(new CorpusManifest.FileEntry("HDFS_2k.log", Vectors.sha256(log), Files.size(log))));
    manifest.write(manifestFile);
    return manifest;
  }

  public static List<StoredRecord> load(ZooConfig config, CorpusManifest manifest, TextEmbedder text) throws IOException {
    Path log = config.dataDir().resolve("logs").resolve("HDFS_2k.log");
    String hash = Vectors.sha256(log);
    CorpusManifest.FileEntry entry = manifest.require("HDFS_2k.log");
    if (entry == null || !entry.sha256().equals(hash)) {
      throw new IOException("HDFS_2k.log does not match the manifest hash");
    }
    List<String> lines = Files.readAllLines(log);
    List<Integer> indexes = Samples.indexes(lines.size(), config.maxLogLines(), false);
    List<StoredRecord> records = new ArrayList<>();
    for (int index : indexes) {
      String line = lines.get(index);
      HdfsLogLine parsed = HdfsLogLine.parse(line);
      String body = "Loghub HDFS_2k line " + (index + 1) + ": " + line;
      String locator = "HDFS_2k.log#" + (index + 1);
      Map<String, String> metadata = new LinkedHashMap<>();
      metadata.put("line_number", Integer.toString(index + 1));
      metadata.put("level", parsed.level);
      metadata.put("component", parsed.component);
      metadata.put("block_id", parsed.blockId);
      metadata.put("publisher", manifest.publisher);
      metadata.put("sample_strategy", "evenly spaced lines of HDFS_2k.log, including the first and last line");
      records.add(StoredRecord.builder()
          .id(Vectors.idFor(manifest.datasetId, locator, text.modelId()))
          .modality("log")
          .datasetId(manifest.datasetId)
          .text(body)
          .textEmbedding(text.embed(body))
          .textEmbeddingModel(text.modelId())
          .textEmbeddingArtifact(text.artifact())
          .metadata(metadata)
          .provenance(TelemetryIngestor.provenance(manifest, locator, Vectors.sha256(line)))
          .build());
    }
    return records;
  }
}
