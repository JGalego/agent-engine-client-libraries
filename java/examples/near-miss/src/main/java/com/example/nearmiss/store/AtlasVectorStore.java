package com.example.nearmiss.store;

import com.example.nearmiss.Vectors;
import com.example.nearmiss.NearMissConfig;
import com.mongodb.MongoCommandException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.SearchIndexModel;
import com.mongodb.client.model.SearchIndexType;
import com.mongodb.client.model.WriteModel;
import com.mongodb.client.model.search.SearchPath;
import com.mongodb.client.model.search.VectorSearchOptions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.bson.Document;
import org.bson.conversions.Bson;

import static com.mongodb.client.model.Aggregates.project;
import static com.mongodb.client.model.Aggregates.vectorSearch;

/**
 * MongoDB Atlas Vector Search, with an exact cosine scan when the index is not ready.
 *
 * <p>Each hit says which mode produced it. An exact scan is not reported as Atlas Vector Search.
 */
public final class AtlasVectorStore implements VectorStore {

  /** The Atlas Vector Search index over the {@code embedding} field. */
  static final String INDEX = "near_miss";

  private static final int EXACT_SCAN_CAP = 5_000;

  private final MongoClient client;
  private final MongoCollection<Document> collection;

  private AtlasVectorStore(MongoClient client, MongoCollection<Document> collection) {
    this.client = client;
    this.collection = collection;
  }

  public static AtlasVectorStore connect(NearMissConfig config) {
    MongoClient client = MongoClients.create(config.mongoUri());
    MongoDatabase database = client.getDatabase(config.mongoDatabase());
    return new AtlasVectorStore(client, database.getCollection(config.mongoCollection()));
  }

  @Override
  public void upsert(List<StoredRecord> records) {
    if (records.isEmpty()) {
      return;
    }
    List<WriteModel<Document>> writes = new ArrayList<>(records.size());
    ReplaceOptions options = new ReplaceOptions().upsert(true);
    for (StoredRecord record : records) {
      writes.add(new com.mongodb.client.model.ReplaceOneModel<>(
          Filters.eq("_id", record.id()), record.toDocument(), options));
    }
    collection.bulkWrite(writes);
  }

  @Override
  public List<StoredRecord> findByIds(List<String> ids) {
    List<StoredRecord> found = new ArrayList<>();
    for (Document doc : collection.find(Filters.in("_id", ids))) {
      found.add(StoredRecord.fromDocument(doc));
    }
    return found;
  }

  @Override
  public SearchResult search(List<Double> query, QueryFilter filter, int limit) {
    if (indexReady(INDEX)) {
      try {
        return atlasSearch(query, filter, limit);
      } catch (MongoCommandException e) {
        SearchResult exact = exact(query, filter, limit);
        return new SearchResult(
            exact.mode(),
            "Atlas $vectorSearch failed (" + e.getErrorCodeName() + "). Fell back to an exact cosine scan.",
            exact.hits());
      }
    }
    SearchResult exact = exact(query, filter, limit);
    return new SearchResult(
        exact.mode(),
        "Vector index " + INDEX + " is not READY. This result is an exact cosine scan, not Atlas Vector Search.",
        exact.hits());
  }

  @Override
  public List<StoredRecord> filter(QueryFilter filter, int limit) {
    List<StoredRecord> found = new ArrayList<>();
    for (Document doc : collection.find(mongoFilter(filter)).limit(limit)) {
      found.add(StoredRecord.fromDocument(doc));
    }
    return found;
  }

  @Override
  public void ensureIndexes(int dimensions) {
    createIfMissing(INDEX, dimensions);
  }

  @Override
  public void close() {
    client.close();
  }

  private SearchResult atlasSearch(List<Double> query, QueryFilter filter, int limit) {
    long candidates = Math.max(limit * 10L, 40L);
    Bson mongoFilter = mongoFilter(filter);
    VectorSearchOptions options = VectorSearchOptions.approximateVectorSearchOptions(candidates);
    if (mongoFilter != null) {
      options = options.filter(mongoFilter);
    }
    Bson stage = vectorSearch(SearchPath.fieldPath("embedding"), query, INDEX, limit, options);
    Bson projection = project(Projections.fields(
        Projections.exclude("embedding"),
        Projections.metaVectorSearchScore("score")));
    List<ScoredRecord> hits = new ArrayList<>();
    for (Document doc : collection.aggregate(List.of(stage, projection))) {
      double score = doc.get("score") instanceof Number number ? number.doubleValue() : Double.NaN;
      doc.remove("score");
      hits.add(new ScoredRecord(StoredRecord.fromDocument(doc), score, "atlas_vector_search"));
    }
    return new SearchResult("atlas_vector_search", null, hits);
  }

  private SearchResult exact(List<Double> query, QueryFilter filter, int limit) {
    List<ScoredRecord> scored = new ArrayList<>();
    int scanned = 0;
    Bson mongoFilter = mongoFilter(filter);
    var iterable = mongoFilter == null ? collection.find() : collection.find(mongoFilter);
    for (Document doc : iterable) {
      if (scanned++ == EXACT_SCAN_CAP) {
        break;
      }
      StoredRecord record = StoredRecord.fromDocument(doc);
      double score = Vectors.cosine(query, record.embedding());
      if (!Double.isNaN(score)) {
        scored.add(new ScoredRecord(record, score, "exact_cosine"));
      }
    }
    scored.sort(Comparator.comparingDouble(ScoredRecord::score).reversed());
    if (scored.size() > limit) {
      scored = new ArrayList<>(scored.subList(0, limit));
    }
    String note = scanned >= EXACT_SCAN_CAP ? "Exact scan stopped at " + EXACT_SCAN_CAP + " documents." : null;
    return new SearchResult("exact_cosine", note, List.copyOf(scored));
  }

  private void createIfMissing(String name, int dimensions) {
    for (Document existing : collection.listSearchIndexes()) {
      if (name.equals(existing.getString("name"))) {
        return;
      }
    }
    Document definition = new Document("fields", List.of(
        new Document("type", "vector")
            .append("path", "embedding")
            .append("numDimensions", dimensions)
            .append("similarity", "cosine"),
        new Document("type", "filter").append("path", "kind"),
        new Document("type", "filter").append("path", "dataset_id")));
    try {
      collection.createSearchIndexes(List.of(
          new SearchIndexModel(name, definition, SearchIndexType.vectorSearch())));
    } catch (MongoCommandException e) {
      System.err.println("Could not create vector index " + name + ": " + e.getErrorMessage());
    }
  }

  private boolean indexReady(String name) {
    try {
      for (Document existing : collection.listSearchIndexes()) {
        if (name.equals(existing.getString("name")) && "READY".equals(existing.getString("status"))) {
          return true;
        }
      }
    } catch (MongoCommandException e) {
      return false;
    }
    return false;
  }

  /** Blocks until the vector index is READY, or returns false when the wait expires. */
  public boolean awaitIndex(DurationLike wait) {
    long deadline = System.nanoTime() + wait.nanos;
    while (System.nanoTime() < deadline) {
      if (indexReady(INDEX)) {
        return true;
      }
      try {
        Thread.sleep(2_000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    return indexReady(INDEX);
  }

  private static Bson mongoFilter(QueryFilter filter) {
    List<Bson> parts = new ArrayList<>();
    if (filter.kind() != null) {
      parts.add(Filters.eq("kind", filter.kind()));
    }
    if (filter.datasetId() != null) {
      parts.add(Filters.eq("dataset_id", filter.datasetId()));
    }
    for (var entry : filter.metadata().entrySet()) {
      parts.add(Filters.eq("metadata." + entry.getKey(), entry.getValue()));
    }
    if (parts.isEmpty()) {
      return null;
    }
    return parts.size() == 1 ? parts.get(0) : Filters.and(parts);
  }

  /** Small stand-in so this file does not import {@link java.time.Duration} only for one call site. */
  public record DurationLike(long nanos) {
    public static DurationLike seconds(long seconds) {
      return new DurationLike(TimeUnit.SECONDS.toNanos(seconds));
    }
  }
}
