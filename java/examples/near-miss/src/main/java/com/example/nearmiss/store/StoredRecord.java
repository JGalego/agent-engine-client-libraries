package com.example.nearmiss.store;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;

/** One stored piece of evidence: a chunk of a public report, its embedding, and where it came from. */
public final class StoredRecord {

  private final String id;
  private final String kind;
  private final String datasetId;
  private final String text;
  private final List<Double> embedding;
  private final String embeddingModel;
  private final String embeddingArtifact;
  private final Map<String, String> metadata;
  private final Map<String, String> provenance;

  private StoredRecord(
      String id,
      String kind,
      String datasetId,
      String text,
      List<Double> embedding,
      String embeddingModel,
      String embeddingArtifact,
      Map<String, String> metadata,
      Map<String, String> provenance) {
    this.id = id;
    this.kind = kind;
    this.datasetId = datasetId;
    this.text = text;
    this.embedding = embedding;
    this.embeddingModel = embeddingModel;
    this.embeddingArtifact = embeddingArtifact;
    this.metadata = metadata;
    this.provenance = provenance;
  }

  public String id() { return id; }
  public String kind() { return kind; }
  public String datasetId() { return datasetId; }
  public String text() { return text; }
  public List<Double> embedding() { return embedding; }
  public String embeddingModel() { return embeddingModel; }
  public Map<String, String> metadata() { return metadata; }
  public Map<String, String> provenance() { return provenance; }

  public Document toDocument() {
    Document doc = new Document("_id", id)
        .append("kind", kind)
        .append("dataset_id", datasetId)
        .append("text", text)
        .append("embedding_model", embeddingModel)
        .append("embedding_artifact", embeddingArtifact)
        .append("metadata", document(metadata))
        .append("provenance", document(provenance));
    if (!embedding.isEmpty()) {
      doc.append("embedding", embedding);
    }
    return doc;
  }

  public static StoredRecord fromDocument(Document doc) {
    return new Builder()
        .id(doc.getString("_id"))
        .kind(doc.getString("kind"))
        .datasetId(doc.getString("dataset_id"))
        .text(doc.getString("text"))
        .embedding(doubles(doc.get("embedding")))
        .embeddingModel(doc.getString("embedding_model"))
        .embeddingArtifact(doc.getString("embedding_artifact"))
        .metadata(strings(doc.get("metadata")))
        .provenance(strings(doc.get("provenance")))
        .build();
  }

  public Map<String, Object> toHit(Double score, String scoreSource) {
    Map<String, Object> hit = new LinkedHashMap<>();
    hit.put("id", id);
    hit.put("kind", kind);
    hit.put("dataset_id", datasetId);
    hit.put("embedding_model", embeddingModel);
    if (score != null) {
      hit.put("score", score);
      hit.put("score_source", scoreSource);
    }
    hit.put("text", truncate(text, 700));
    hit.put("metadata", metadata);
    hit.put("provenance", provenance);
    return hit;
  }

  public static Builder builder() {
    return new Builder();
  }

  private static Document document(Map<String, String> values) {
    Document doc = new Document();
    values.forEach(doc::append);
    return doc;
  }

  private static List<Double> doubles(Object value) {
    if (!(value instanceof List<?> list)) {
      return List.of();
    }
    List<Double> out = new ArrayList<>(list.size());
    for (Object item : list) {
      if (item instanceof Number number) {
        out.add(number.doubleValue());
      }
    }
    return List.copyOf(out);
  }

  private static Map<String, String> strings(Object value) {
    if (!(value instanceof Document doc)) {
      return Map.of();
    }
    Map<String, String> out = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : doc.entrySet()) {
      if (entry.getValue() != null) {
        out.put(entry.getKey(), String.valueOf(entry.getValue()));
      }
    }
    return Map.copyOf(out);
  }

  private static String truncate(String value, int max) {
    if (value == null || value.length() <= max) {
      return value;
    }
    return value.substring(0, max) + "...";
  }

  /** Builder for {@link StoredRecord}. */
  public static final class Builder {
    private String id;
    private String kind;
    private String datasetId;
    private String text = "";
    private List<Double> embedding = List.of();
    private String embeddingModel = "";
    private String embeddingArtifact = "";
    private Map<String, String> metadata = Map.of();
    private Map<String, String> provenance = Map.of();

    public Builder id(String v) { id = v; return this; }
    public Builder kind(String v) { kind = v; return this; }
    public Builder datasetId(String v) { datasetId = v; return this; }
    public Builder text(String v) { text = v == null ? "" : v; return this; }
    public Builder embedding(List<Double> v) { embedding = v == null ? List.of() : List.copyOf(v); return this; }
    public Builder embeddingModel(String v) { embeddingModel = v == null ? "" : v; return this; }
    public Builder embeddingArtifact(String v) { embeddingArtifact = v == null ? "" : v; return this; }
    public Builder metadata(Map<String, String> v) { metadata = v == null ? Map.of() : Map.copyOf(v); return this; }
    public Builder provenance(Map<String, String> v) { provenance = v == null ? Map.of() : Map.copyOf(v); return this; }

    public StoredRecord build() {
      if (id == null || kind == null || datasetId == null) {
        throw new IllegalArgumentException("id, kind, and datasetId are required");
      }
      return new StoredRecord(
          id, kind, datasetId, text, embedding, embeddingModel, embeddingArtifact, metadata, provenance);
    }
  }
}
