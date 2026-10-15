package com.example.embeddingzoo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.embeddingzoo.embed.TextEmbedder;
import com.example.embeddingzoo.store.InMemoryVectorStore;
import com.example.embeddingzoo.store.StoredRecord;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ZooToolkitTest {

  @Test
  void textSearchRanksTheMatchingCardAndRefusesToEmbedVibrationFromText() {
    InMemoryVectorStore store = new InMemoryVectorStore();
    store.upsert(List.of(
        card("bearing", "telemetry", "nasa-ims-bearings", "bearing vibration kurtosis is elevated"),
        card("holiday", "incident", "netflix-public-postmortem", "Christmas Eve load balancer outage")));
    ZooToolkit toolkit = new ZooToolkit(config(), store, new KeywordEmbedder());

    Map<String, Object> search = toolkit.semanticSearch(Map.of("query", "bearing kurtosis", "limit", 1));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> hits = (List<Map<String, Object>>) search.get("hits");
    assertEquals("bearing", hits.get(0).get("id"));
    assertEquals("in_memory_cosine", search.get("search_mode"));

    Map<String, Object> refused = toolkit.semanticSearch(Map.of("query", "bearing kurtosis", "space", "vibration"));
    assertTrue(String.valueOf(refused.get("error")).contains("no text encoder"));
  }

  @Test
  void metadataFilterDoesNotUseTheEmbedding() {
    InMemoryVectorStore store = new InMemoryVectorStore();
    store.upsert(List.of(card("log-1", "log", "loghub-hdfs", "INFO PacketResponder")));
    ZooToolkit toolkit = new ZooToolkit(config(), store, new KeywordEmbedder());
    Map<String, Object> filtered = toolkit.filterMetadata(Map.of("modality", "log", "dataset_id", "loghub-hdfs"));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> hits = (List<Map<String, Object>>) filtered.get("hits");
    assertEquals(1, hits.size());
    assertFalse(filtered.containsKey("error"));
  }

  @Test
  void anEmptyFilterResultListsTheValidValues() {
    ZooToolkit toolkit = new ZooToolkit(config(), new InMemoryVectorStore(), new KeywordEmbedder());
    Map<String, Object> filtered = toolkit.filterMetadata(Map.of("modality", "telemetry"));
    assertTrue(String.valueOf(filtered.get("hint")).contains("nasa-ims-bearings"));
  }

  @Test
  void theAppRegistersTheRetrievalTools() {
    assertEquals("embedding-zoo", EmbeddingZooApp.app.name());
    assertTrue(EmbeddingZooApp.app.findTool("semantic_search").isPresent());
    assertTrue(EmbeddingZooApp.app.findTool("filter_metadata").isPresent());
    assertTrue(EmbeddingZooApp.app.findTool("correlate_evidence").isPresent());
    assertTrue(EmbeddingZooAgent.SYSTEM_PROMPT.contains("analogous pattern"));
    assertTrue(EmbeddingZooAgent.SYSTEM_PROMPT.contains("verified_connection"));
  }

  private static StoredRecord card(String id, String modality, String dataset, String text) {
    return StoredRecord.builder()
        .id(id)
        .modality(modality)
        .datasetId(dataset)
        .text(text)
        .textEmbedding(new KeywordEmbedder().embed(text))
        .textEmbeddingModel("test/keywords")
        .metadata(Map.of("publisher", "Example"))
        .provenance(Map.of("source_url", "https://example.test/" + dataset, "license", "test"))
        .build();
  }

  private static ZooConfig config() {
    return ZooConfig.load();
  }

  /** Two-axis embedder so the test can name which idea a string is about. */
  private static final class KeywordEmbedder implements TextEmbedder {
    @Override public String modelId() { return "test/keywords"; }
    @Override public String artifact() { return "test"; }
    @Override public int dimensions() { return 2; }

    @Override
    public List<Double> embed(String text) {
      String lower = text.toLowerCase();
      double bearing = lower.contains("bearing") || lower.contains("kurtosis") ? 1 : 0;
      double holiday = lower.contains("christmas") || lower.contains("balancer") ? 1 : 0;
      double norm = Math.sqrt(bearing * bearing + holiday * holiday);
      if (norm == 0) {
        return List.of(0.0, 0.0);
      }
      return List.of(bearing / norm, holiday / norm);
    }
  }
}
