package com.example.embeddingzoo;

import com.example.embeddingzoo.correlate.EvidenceCorrelator;
import com.example.embeddingzoo.embed.TextEmbedder;
import com.example.embeddingzoo.store.StoredRecord;
import com.example.embeddingzoo.store.VectorStore;
import com.example.embeddingzoo.store.VectorStore.QueryFilter;
import com.example.embeddingzoo.store.VectorStore.ScoredRecord;
import com.example.embeddingzoo.store.VectorStore.SearchResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Semantic search, metadata filtering, and cross-modal correlation over one vector store. */
public final class ZooToolkit implements AutoCloseable {

  private final ZooConfig config;
  private final VectorStore store;
  private final TextEmbedder embedder;

  public ZooToolkit(ZooConfig config, VectorStore store, TextEmbedder embedder) {
    this.config = config;
    this.store = store;
    this.embedder = embedder;
  }

  public VectorStore store() {
    return store;
  }

  private static final String FILTER_HINT = "No stored record matches the filter. modality is one of telemetry, image, log, "
      + "incident, code. dataset_id is one of " + String.join(", ", Datasets.IMS_ID, Datasets.MVTEC_ID,
          Datasets.LOG_ID, Datasets.INCIDENT_ID, Datasets.CODE_ID) + ".";

  public TextEmbedder embedder() {
    return embedder;
  }

  public Map<String, Object> semanticSearch(Map<String, Object> args) {
    String space = Args.str(args, "space", "text");
    if (!"text".equals(space) && !"vibration".equals(space) && !"image".equals(space)) {
      return error("space must be text, vibration, or image.");
    }
    String like = Args.str(args, "like", null);
    String query = Args.str(args, "query", null);
    int limit = Math.min(20, Math.max(1, Args.integer(args, "limit", 5)));
    QueryFilter filter = QueryFilter.fromArgs(args);
    List<Double> vector;
    String vectorNote;
    if (like != null) {
      List<StoredRecord> found = store.findByIds(List.of(like));
      if (found.isEmpty()) {
        return error("No stored record has id " + like + ".");
      }
      vector = found.get(0).vector(space);
      if (vector == null) {
        return error("Record " + like + " has no " + space + " embedding.");
      }
      vectorNote = "The query vector is the " + space + " embedding of record " + like + ", model " + found.get(0).modelFor(space) + ".";
    } else if ("text".equals(space)) {
      if (query == null) {
        return error("Provide query text, or like with a stored record id.");
      }
      vector = embedder.embed(query);
      vectorNote = "The query text was embedded with " + embedder.modelId() + " (" + embedder.artifact() + ").";
    } else {
      return error(
          "The " + space + " space has no text encoder. Pass like as the id of a record that already has a "
              + space + " embedding. A natural-language query is not converted into a vibration-feature or histogram vector.");
    }
    SearchResult result = store.search(space, vector, filter, limit);
    List<Map<String, Object>> hits = new ArrayList<>();
    for (ScoredRecord hit : result.hits()) {
      hits.add(hit.record().toHit(hit.score(), hit.scoreSource(), space));
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("space", space);
    out.put("search_mode", result.mode());
    if (result.note() != null) {
      out.put("search_note", result.note());
    }
    out.put("vector_note", vectorNote);
    out.put("hits", hits);
    if (hits.isEmpty() && !filter.isEmpty()) {
      out.put("hint", FILTER_HINT);
    }
    return out;
  }

  public Map<String, Object> filterMetadata(Map<String, Object> args) {
    QueryFilter filter = QueryFilter.fromArgs(args);
    if (filter.isEmpty()) {
      return error("Provide modality, dataset_id, or metadata.");
    }
    int limit = Math.min(50, Math.max(1, Args.integer(args, "limit", 10)));
    List<Map<String, Object>> hits = new ArrayList<>();
    for (StoredRecord record : store.filter(filter, limit)) {
      hits.add(record.toHit(null, null, "text"));
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("filter", Map.of(
        "modality", String.valueOf(filter.modality()),
        "dataset_id", String.valueOf(filter.datasetId()),
        "metadata", filter.metadata()));
    out.put("hits", hits);
    if (hits.isEmpty() && !filter.isEmpty()) {
      out.put("hint", FILTER_HINT);
    }
    return out;
  }

  public Map<String, Object> correlate(Map<String, Object> args) {
    List<String> ids = Args.strings(args, "ids");
    if (!ids.isEmpty()) {
      List<StoredRecord> records = store.findByIds(ids);
      Map<String, Object> report = EvidenceCorrelator.report(records, config.analogyCutoff());
      List<String> missing = new ArrayList<>();
      for (String id : ids) {
        boolean found = records.stream().anyMatch(record -> record.id().equals(id));
        if (!found) {
          missing.add(id);
        }
      }
      if (!missing.isEmpty()) {
        report.put("missing_ids", missing);
      }
      return report;
    }
    String query = Args.str(args, "query", null);
    if (query == null) {
      return error("Provide ids or a query.");
    }
    List<Double> vector = embedder.embed(query);
    List<StoredRecord> picked = new ArrayList<>();
    Map<String, Object> byModality = new LinkedHashMap<>();
    for (String modality : List.of("telemetry", "image", "log", "incident", "code")) {
      SearchResult result = store.search("text", vector, new QueryFilter(modality, null, Map.of()), 1);
      if (result.hits().isEmpty()) {
        byModality.put(modality, Map.of("hits", List.of(), "search_mode", result.mode()));
        continue;
      }
      ScoredRecord hit = result.hits().get(0);
      picked.add(hit.record());
      byModality.put(modality, Map.of(
          "search_mode", result.mode(),
          "hit", hit.record().toHit(hit.score(), hit.scoreSource(), "text")));
    }
    Map<String, Object> report = EvidenceCorrelator.report(picked, config.analogyCutoff());
    report.put("search_space", "text");
    report.put(
        "search_note",
        "Each modality contributed at most one nearest record in the shared text embedding space. "
            + "Vibration and histogram vectors were not used as the query. "
            + "Pairs from different datasets are analogous patterns or independent records.");
    report.put("hits_by_modality", byModality);
    return report;
  }

  @Override
  public void close() {
    try {
      store.close();
    } finally {
      if (embedder instanceof AutoCloseable closeable) {
        try {
          closeable.close();
        } catch (Exception e) {
          throw new IllegalStateException(e);
        }
      }
    }
  }

  private static Map<String, Object> error(String message) {
    return Map.of("error", message);
  }
}
