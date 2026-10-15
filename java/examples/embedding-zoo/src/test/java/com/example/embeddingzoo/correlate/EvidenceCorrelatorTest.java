package com.example.embeddingzoo.correlate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.embeddingzoo.store.StoredRecord;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EvidenceCorrelatorTest {

  @Test
  void sameSnapshotIsAVerifiedRecording() {
    Map<String, Object> pair = EvidenceCorrelator.pair(
        record("nasa-ims-bearings", "telemetry", "a", "rec-1", "1", List.of(1.0, 0.0)),
        record("nasa-ims-bearings", "telemetry", "b", "rec-1", "2", List.of(0.0, 1.0)),
        0.45);
    assertEquals("verified_same_recording", pair.get("relationship"));
    assertEquals(true, pair.get("verified_connection"));
    assertFalse(String.valueOf(pair.get("explanation")).toLowerCase().contains("caused"));
  }

  @Test
  void differentDatasetsStayUnverifiedEvenWhenVectorsMatch() {
    List<Double> vector = List.of(1.0, 0.0);
    Map<String, Object> pair = EvidenceCorrelator.pair(
        record("nasa-ims-bearings", "telemetry", "a", "rec-1", "1", vector),
        record("loghub-hdfs", "log", "b", "", "", vector),
        0.45);
    assertEquals("analogous_pattern", pair.get("relationship"));
    assertEquals(false, pair.get("verified_connection"));
    String explanation = String.valueOf(pair.get("explanation"));
    assertTrue(explanation.contains("not a verified connection"));
    assertTrue(explanation.contains("independently"));
  }

  @Test
  void differentModelsAreNotCompared() {
    StoredRecord left = StoredRecord.builder()
        .id("a").modality("telemetry").datasetId("nasa-ims-bearings").text("bearing")
        .textEmbedding(List.of(1.0, 0.0)).textEmbeddingModel("model-a")
        .metadata(Map.of()).provenance(Map.of()).build();
    StoredRecord right = StoredRecord.builder()
        .id("b").modality("log").datasetId("loghub-hdfs").text("block")
        .textEmbedding(List.of(1.0, 0.0, 0.0)).textEmbeddingModel("model-b")
        .metadata(Map.of()).provenance(Map.of()).build();
    Map<String, Object> pair = EvidenceCorrelator.pair(left, right, 0.45);
    assertEquals("not_comparable", pair.get("relationship"));
    assertEquals(false, pair.get("verified_connection"));
    assertFalse(pair.containsKey("text_cosine"));
  }

  private static StoredRecord record(
      String dataset, String modality, String id, String recording, String channel, List<Double> vector) {
    return StoredRecord.builder()
        .id(id)
        .modality(modality)
        .datasetId(dataset)
        .text(modality + " card")
        .textEmbedding(vector)
        .textEmbeddingModel("sentence-transformers/all-MiniLM-L6-v2")
        .metadata(Map.of("recording_id", recording, "channel", channel, "publisher", "Example"))
        .provenance(Map.of("source_url", "https://example.test/" + dataset))
        .build();
  }
}
