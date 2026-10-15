package com.example.embeddingzoo.store;

import java.util.List;
import java.util.Map;

/** Where evidence documents and their vectors live. */
public interface VectorStore extends AutoCloseable {

  void upsert(List<StoredRecord> records);

  List<StoredRecord> findByIds(List<String> ids);

  SearchResult search(String space, List<Double> query, QueryFilter filter, int limit);

  List<StoredRecord> filter(QueryFilter filter, int limit);

  /** Ensures vector indexes when the backend has them. In-memory stores do nothing. */
  default void ensureIndexes(int textDimensions) {}

  @Override
  default void close() {}

  record ScoredRecord(StoredRecord record, double score, String scoreSource) {}

  record SearchResult(String mode, String note, List<ScoredRecord> hits) {}

  record QueryFilter(String modality, String datasetId, Map<String, String> metadata) {
    public QueryFilter {
      metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static QueryFilter fromArgs(Map<String, Object> args) {
      return new QueryFilter(
          com.example.embeddingzoo.Args.str(args, "modality", null),
          com.example.embeddingzoo.Args.str(args, "dataset_id", null),
          com.example.embeddingzoo.Args.stringMap(args, "metadata"));
    }

    public boolean isEmpty() {
      return modality == null && datasetId == null && metadata.isEmpty();
    }

    public boolean matches(StoredRecord record) {
      if (modality != null && !modality.equals(record.modality())) {
        return false;
      }
      if (datasetId != null && !datasetId.equals(record.datasetId())) {
        return false;
      }
      for (Map.Entry<String, String> entry : metadata.entrySet()) {
        if (!entry.getValue().equals(record.metadata().get(entry.getKey()))) {
          return false;
        }
      }
      return true;
    }
  }
}
