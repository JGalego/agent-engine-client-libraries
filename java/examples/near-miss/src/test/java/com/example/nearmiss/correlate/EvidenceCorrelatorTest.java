package com.example.nearmiss.correlate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.nearmiss.Datasets;
import com.example.nearmiss.store.StoredRecord;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EvidenceCorrelatorTest {

  @Test
  void twoChunksOfOneReportAreVerified() {
    Map<String, Object> pair = EvidenceCorrelator.pair(
        record(Datasets.MAUDE_ID, "a", Map.of("document_url", "https://example.test/r1"), List.of(1.0, 0.0)),
        record(Datasets.MAUDE_ID, "b", Map.of("document_url", "https://example.test/r1"), List.of(0.0, 1.0)),
        0.45);
    assertEquals("verified_same_report", pair.get("relationship"));
    assertEquals(true, pair.get("verified_connection"));
    assertFalse(String.valueOf(pair.get("explanation")).toLowerCase().contains("caused"));
  }

  @Test
  void aSharedProductCodeIsVerifiedAsAGroupingOnly() {
    Map<String, Object> pair = EvidenceCorrelator.pair(
        record(Datasets.MAUDE_ID, "a", Map.of("product_code", "LZG", "document_url", "r1"), List.of(1.0, 0.0)),
        record(Datasets.MAUDE_ID, "b", Map.of("product_code", "LZG", "document_url", "r2"), List.of(0.0, 1.0)),
        0.45);
    assertEquals("verified_same_product_code", pair.get("relationship"));
    assertEquals(true, pair.get("verified_connection"));
    assertTrue(String.valueOf(pair.get("explanation")).contains("does not verify that the two reports describe the same event"));
  }

  @Test
  void differentDatasetsStayUnverifiedEvenWhenVectorsMatch() {
    List<Double> vector = List.of(1.0, 0.0);
    Map<String, Object> pair = EvidenceCorrelator.pair(
        record(Datasets.MAUDE_ID, "a", Map.of("product_code", "LZG"), vector),
        record(Datasets.NHTSA_ID, "b", Map.of("vehicle", "TESLA MODEL 3 2020"), vector),
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
        .id("a").kind(Datasets.KIND_DEVICE).datasetId(Datasets.MAUDE_ID).text("pump")
        .embedding(List.of(1.0, 0.0)).embeddingModel("model-a")
        .metadata(Map.of()).provenance(Map.of()).build();
    StoredRecord right = StoredRecord.builder()
        .id("b").kind(Datasets.KIND_VEHICLE).datasetId(Datasets.NHTSA_ID).text("brake")
        .embedding(List.of(1.0, 0.0, 0.0)).embeddingModel("model-b")
        .metadata(Map.of()).provenance(Map.of()).build();
    Map<String, Object> pair = EvidenceCorrelator.pair(left, right, 0.45);
    assertEquals("not_comparable", pair.get("relationship"));
    assertEquals(false, pair.get("verified_connection"));
    assertFalse(pair.containsKey("text_cosine"));
  }

  private static StoredRecord record(String dataset, String id, Map<String, String> metadata, List<Double> vector) {
    Map<String, String> all = new java.util.LinkedHashMap<>(metadata);
    all.put("publisher", "Example");
    return StoredRecord.builder()
        .id(id)
        .kind(dataset.equals(Datasets.MAUDE_ID) ? Datasets.KIND_DEVICE : Datasets.KIND_VEHICLE)
        .datasetId(dataset)
        .text("report")
        .embedding(vector)
        .embeddingModel("sentence-transformers/all-MiniLM-L6-v2")
        .metadata(all)
        .provenance(Map.of("source_url", "https://example.test/" + dataset))
        .build();
  }
}
