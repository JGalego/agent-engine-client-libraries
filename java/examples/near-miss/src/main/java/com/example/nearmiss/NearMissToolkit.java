package com.example.nearmiss;

import com.example.nearmiss.correlate.EvidenceCorrelator;
import com.example.nearmiss.embed.TextEmbedder;
import com.example.nearmiss.store.StoredRecord;
import com.example.nearmiss.store.VectorStore;
import com.example.nearmiss.store.VectorStore.QueryFilter;
import com.example.nearmiss.store.VectorStore.ScoredRecord;
import com.example.nearmiss.store.VectorStore.SearchResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Semantic search, metadata filtering, and cross-dataset correlation over one vector store. */
public final class NearMissToolkit implements AutoCloseable {

  private final NearMissConfig config;
  private final VectorStore store;
  private final TextEmbedder embedder;

  public NearMissToolkit(NearMissConfig config, VectorStore store, TextEmbedder embedder) {
    this.config = config;
    this.store = store;
    this.embedder = embedder;
  }

  public VectorStore store() {
    return store;
  }

  private static final List<String> KINDS = List.of(Datasets.KIND_DEVICE, Datasets.KIND_VEHICLE, Datasets.KIND_AVIATION);

  private static final String FILTER_HINT = "No stored record matches the filter. kind is one of "
      + String.join(", ", KINDS) + ". dataset_id is one of "
      + String.join(", ", Datasets.MAUDE_ID, Datasets.NHTSA_ID, Datasets.ASRS_ID) + ".";

  public TextEmbedder embedder() {
    return embedder;
  }

  public Map<String, Object> semanticSearch(Map<String, Object> args) {
    String query = Args.str(args, "query", null);
    if (query == null) {
      return error("Provide the query text.");
    }
    int limit = Math.min(20, Math.max(1, Args.integer(args, "limit", 5)));
    QueryFilter filter = QueryFilter.fromArgs(args);
    SearchResult result = store.search(embedder.embed(query), filter, limit);
    List<Map<String, Object>> hits = new ArrayList<>();
    for (ScoredRecord hit : result.hits()) {
      hits.add(hit.record().toHit(hit.score(), hit.scoreSource()));
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("search_mode", result.mode());
    if (result.note() != null) {
      out.put("search_note", result.note());
    }
    out.put("query_embedding", embedder.modelId() + " (" + embedder.artifact() + ")");
    out.put("disclaimer", Datasets.DISCLAIMER);
    out.put("hits", hits);
    if (hits.isEmpty() && !filter.isEmpty()) {
      out.put("hint", FILTER_HINT);
    }
    return out;
  }

  public Map<String, Object> filterMetadata(Map<String, Object> args) {
    QueryFilter filter = QueryFilter.fromArgs(args);
    if (filter.isEmpty()) {
      return error("Provide kind, dataset_id, or metadata.");
    }
    int limit = Math.min(50, Math.max(1, Args.integer(args, "limit", 10)));
    List<Map<String, Object>> hits = new ArrayList<>();
    for (StoredRecord record : store.filter(filter, limit)) {
      hits.add(record.toHit(null, null));
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("filter", Map.of(
        "kind", String.valueOf(filter.kind()),
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
    Map<String, Object> byKind = new LinkedHashMap<>();
    for (String kind : KINDS) {
      SearchResult result = store.search(vector, new QueryFilter(kind, null, Map.of()), 1);
      if (result.hits().isEmpty()) {
        byKind.put(kind, Map.of("hits", List.of(), "search_mode", result.mode()));
        continue;
      }
      ScoredRecord hit = result.hits().get(0);
      picked.add(hit.record());
      byKind.put(kind, Map.of(
          "search_mode", result.mode(),
          "hit", hit.record().toHit(hit.score(), hit.scoreSource())));
    }
    Map<String, Object> report = EvidenceCorrelator.report(picked, config.analogyCutoff());
    report.put(
        "search_note",
        "Each kind contributed at most one nearest record. "
            + "Pairs from different datasets are analogous patterns or independent records.");
    report.put("hits_by_kind", byKind);
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
