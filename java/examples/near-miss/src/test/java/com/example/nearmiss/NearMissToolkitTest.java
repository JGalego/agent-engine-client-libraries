package com.example.nearmiss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.nearmiss.embed.TextEmbedder;
import com.example.nearmiss.store.InMemoryVectorStore;
import com.example.nearmiss.store.StoredRecord;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NearMissToolkitTest {

  @Test
  void searchRanksTheMatchingReportFirst() {
    InMemoryVectorStore store = new InMemoryVectorStore();
    store.upsert(List.of(
        record("pump", Datasets.KIND_DEVICE, Datasets.MAUDE_ID, "infusion pump delivered too much insulin"),
        record("brake", Datasets.KIND_VEHICLE, Datasets.NHTSA_ID, "the brake pedal went to the floor")));
    NearMissToolkit toolkit = new NearMissToolkit(config(), store, new KeywordEmbedder());

    Map<String, Object> search = toolkit.semanticSearch(Map.of("query", "insulin overdose", "limit", 1));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> hits = (List<Map<String, Object>>) search.get("hits");
    assertEquals("pump", hits.get(0).get("id"));
    assertEquals("in_memory_cosine", search.get("search_mode"));
    assertTrue(String.valueOf(search.get("disclaimer")).contains("not findings"));
  }

  @Test
  void aKindFilterNarrowsTheSearch() {
    InMemoryVectorStore store = new InMemoryVectorStore();
    store.upsert(List.of(
        record("pump", Datasets.KIND_DEVICE, Datasets.MAUDE_ID, "insulin pump alarm"),
        record("car", Datasets.KIND_VEHICLE, Datasets.NHTSA_ID, "insulin pump warning light on the dashboard")));
    NearMissToolkit toolkit = new NearMissToolkit(config(), store, new KeywordEmbedder());

    Map<String, Object> search = toolkit.semanticSearch(Map.of("query", "insulin", "kind", "vehicle"));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> hits = (List<Map<String, Object>>) search.get("hits");
    assertEquals(1, hits.size());
    assertEquals("car", hits.get(0).get("id"));
  }

  @Test
  void searchNeedsAQuery() {
    NearMissToolkit toolkit = new NearMissToolkit(config(), new InMemoryVectorStore(), new KeywordEmbedder());
    assertTrue(toolkit.semanticSearch(Map.of()).containsKey("error"));
  }

  @Test
  void metadataFilterDoesNotUseTheEmbedding() {
    InMemoryVectorStore store = new InMemoryVectorStore();
    store.upsert(List.of(record("pump", Datasets.KIND_DEVICE, Datasets.MAUDE_ID, "insulin pump alarm")));
    NearMissToolkit toolkit = new NearMissToolkit(config(), store, new KeywordEmbedder());
    Map<String, Object> filtered = toolkit.filterMetadata(Map.of("kind", "device", "dataset_id", Datasets.MAUDE_ID));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> hits = (List<Map<String, Object>>) filtered.get("hits");
    assertEquals(1, hits.size());
    assertFalse(filtered.containsKey("error"));
  }

  @Test
  void anEmptyFilterResultListsTheValidValues() {
    NearMissToolkit toolkit = new NearMissToolkit(config(), new InMemoryVectorStore(), new KeywordEmbedder());
    Map<String, Object> filtered = toolkit.filterMetadata(Map.of("kind", "device"));
    assertTrue(String.valueOf(filtered.get("hint")).contains(Datasets.NHTSA_ID));
  }

  @Test
  void theAppRegistersTheRetrievalTools() {
    assertEquals("near-miss", NearMissApp.app.name());
    assertTrue(NearMissApp.app.findTool("semantic_search").isPresent());
    assertTrue(NearMissApp.app.findTool("filter_metadata").isPresent());
    assertTrue(NearMissApp.app.findTool("correlate_evidence").isPresent());
    assertTrue(NearMissAgent.SYSTEM_PROMPT.contains("analogous pattern"));
    assertTrue(NearMissAgent.SYSTEM_PROMPT.contains("verified_connection"));
    assertTrue(NearMissAgent.SYSTEM_PROMPT.contains("Never say that a device, a vehicle or an aircraft is defective"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void filterArgumentsAreLimitedToTheStoredValues() {
    for (String tool : List.of("semantic_search", "filter_metadata")) {
      Map<String, Object> properties =
          (Map<String, Object>) NearMissApp.app.findTool(tool).orElseThrow().argsSchema().get("properties");
      assertTrue(((Map<String, Object>) properties.get("kind")).get("enum").toString().contains("device"));
      assertTrue(((Map<String, Object>) properties.get("dataset_id")).get("enum").toString().contains(Datasets.MAUDE_ID));
    }
  }

  private static StoredRecord record(String id, String kind, String dataset, String text) {
    return StoredRecord.builder()
        .id(id)
        .kind(kind)
        .datasetId(dataset)
        .text(text)
        .embedding(new KeywordEmbedder().embed(text))
        .embeddingModel("test/keywords")
        .metadata(Map.of("publisher", "Example"))
        .provenance(Map.of("source_url", "https://example.test/" + dataset, "license", "test"))
        .build();
  }

  private static NearMissConfig config() {
    return NearMissConfig.load();
  }

  /** Two-axis embedder so the test can name which idea a string is about. */
  private static final class KeywordEmbedder implements TextEmbedder {
    @Override public String modelId() { return "test/keywords"; }
    @Override public String artifact() { return "test"; }
    @Override public int dimensions() { return 2; }

    @Override
    public List<Double> embed(String text) {
      String lower = text.toLowerCase();
      double insulin = lower.contains("insulin") ? 1 : 0;
      double brake = lower.contains("brake") ? 1 : 0;
      double norm = Math.sqrt(insulin * insulin + brake * brake);
      if (norm == 0) {
        return List.of(0.0, 0.0);
      }
      return List.of(insulin / norm, brake / norm);
    }
  }
}
