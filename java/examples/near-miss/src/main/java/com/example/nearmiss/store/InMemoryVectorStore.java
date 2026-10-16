package com.example.nearmiss.store;

import com.example.nearmiss.Vectors;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** Exact cosine search over records held in the process. */
public final class InMemoryVectorStore implements VectorStore {

  private final ConcurrentHashMap<String, StoredRecord> records = new ConcurrentHashMap<>();

  @Override
  public void upsert(List<StoredRecord> batch) {
    for (StoredRecord record : batch) {
      records.put(record.id(), record);
    }
  }

  @Override
  public List<StoredRecord> findByIds(List<String> ids) {
    List<StoredRecord> found = new ArrayList<>();
    for (String id : ids) {
      StoredRecord record = records.get(id);
      if (record != null) {
        found.add(record);
      }
    }
    return found;
  }

  @Override
  public SearchResult search(List<Double> query, QueryFilter filter, int limit) {
    List<ScoredRecord> scored = new ArrayList<>();
    for (StoredRecord record : records.values()) {
      if (!filter.matches(record)) {
        continue;
      }
      List<Double> vector = record.embedding();
      double score = Vectors.cosine(query, vector);
      if (!Double.isNaN(score)) {
        scored.add(new ScoredRecord(record, score, "in_memory_cosine"));
      }
    }
    scored.sort(Comparator.comparingDouble(ScoredRecord::score).reversed());
    if (scored.size() > limit) {
      scored = new ArrayList<>(scored.subList(0, limit));
    }
    return new SearchResult("in_memory_cosine", null, List.copyOf(scored));
  }

  @Override
  public List<StoredRecord> filter(QueryFilter filter, int limit) {
    List<StoredRecord> found = new ArrayList<>();
    for (StoredRecord record : records.values()) {
      if (filter.matches(record)) {
        found.add(record);
        if (found.size() == limit) {
          break;
        }
      }
    }
    return found;
  }

  public int size() {
    return records.size();
  }
}
