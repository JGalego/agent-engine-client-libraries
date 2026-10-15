package com.example.embeddingzoo.store;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;

/** One stored piece of evidence, including the vectors that search uses. */
public final class StoredRecord {

  private final String id;
  private final String modality;
  private final String datasetId;
  private final String text;
  private final List<Double> textEmbedding;
  private final String textEmbeddingModel;
  private final String textEmbeddingArtifact;
  private final List<Double> vibrationEmbedding;
  private final String vibrationEmbeddingModel;
  private final List<Double> imageEmbedding;
  private final String imageEmbeddingModel;
  private final Map<String, String> metadata;
  private final Map<String, String> provenance;

  private StoredRecord(
      String id,
      String modality,
      String datasetId,
      String text,
      List<Double> textEmbedding,
      String textEmbeddingModel,
      String textEmbeddingArtifact,
      List<Double> vibrationEmbedding,
      String vibrationEmbeddingModel,
      List<Double> imageEmbedding,
      String imageEmbeddingModel,
      Map<String, String> metadata,
      Map<String, String> provenance) {
    this.id = id;
    this.modality = modality;
    this.datasetId = datasetId;
    this.text = text;
    this.textEmbedding = textEmbedding;
    this.textEmbeddingModel = textEmbeddingModel;
    this.textEmbeddingArtifact = textEmbeddingArtifact;
    this.vibrationEmbedding = vibrationEmbedding;
    this.vibrationEmbeddingModel = vibrationEmbeddingModel;
    this.imageEmbedding = imageEmbedding;
    this.imageEmbeddingModel = imageEmbeddingModel;
    this.metadata = metadata;
    this.provenance = provenance;
  }

  public String id() { return id; }
  public String modality() { return modality; }
  public String datasetId() { return datasetId; }
  public String text() { return text; }
  public List<Double> textEmbedding() { return textEmbedding; }
  public String textEmbeddingModel() { return textEmbeddingModel; }
  public Map<String, String> metadata() { return metadata; }
  public Map<String, String> provenance() { return provenance; }

  public List<Double> vector(String space) {
    return switch (space) {
      case "text" -> emptyToNull(textEmbedding);
      case "vibration" -> emptyToNull(vibrationEmbedding);
      case "image" -> emptyToNull(imageEmbedding);
      default -> null;
    };
  }

  public String modelFor(String space) {
    return switch (space) {
      case "text" -> textEmbeddingModel;
      case "vibration" -> vibrationEmbeddingModel;
      case "image" -> imageEmbeddingModel;
      default -> "";
    };
  }

  public Document toDocument() {
    Document doc = new Document("_id", id)
        .append("modality", modality)
        .append("dataset_id", datasetId)
        .append("text", text)
        .append("text_embedding_model", textEmbeddingModel)
        .append("text_embedding_artifact", textEmbeddingArtifact)
        .append("metadata", document(metadata))
        .append("provenance", document(provenance));
    if (textEmbedding != null && !textEmbedding.isEmpty()) {
      doc.append("text_embedding", textEmbedding);
    }
    if (vibrationEmbedding != null && !vibrationEmbedding.isEmpty()) {
      doc.append("vibration_embedding", vibrationEmbedding);
      doc.append("vibration_embedding_model", vibrationEmbeddingModel);
    }
    if (imageEmbedding != null && !imageEmbedding.isEmpty()) {
      doc.append("image_embedding", imageEmbedding);
      doc.append("image_embedding_model", imageEmbeddingModel);
    }
    return doc;
  }

  public static StoredRecord fromDocument(Document doc) {
    return new Builder()
        .id(doc.getString("_id"))
        .modality(doc.getString("modality"))
        .datasetId(doc.getString("dataset_id"))
        .text(doc.getString("text"))
        .textEmbedding(doubles(doc.get("text_embedding")))
        .textEmbeddingModel(doc.getString("text_embedding_model"))
        .textEmbeddingArtifact(doc.getString("text_embedding_artifact"))
        .vibrationEmbedding(doubles(doc.get("vibration_embedding")))
        .vibrationEmbeddingModel(doc.getString("vibration_embedding_model"))
        .imageEmbedding(doubles(doc.get("image_embedding")))
        .imageEmbeddingModel(doc.getString("image_embedding_model"))
        .metadata(strings(doc.get("metadata")))
        .provenance(strings(doc.get("provenance")))
        .build();
  }

  public Map<String, Object> toHit(Double score, String scoreSource, String space) {
    Map<String, Object> hit = new LinkedHashMap<>();
    hit.put("id", id);
    hit.put("modality", modality);
    hit.put("dataset_id", datasetId);
    hit.put("space", space);
    hit.put("embedding_model", modelFor(space));
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

  private static List<Double> emptyToNull(List<Double> values) {
    return values == null || values.isEmpty() ? null : values;
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
    private String modality;
    private String datasetId;
    private String text = "";
    private List<Double> textEmbedding = List.of();
    private String textEmbeddingModel = "";
    private String textEmbeddingArtifact = "";
    private List<Double> vibrationEmbedding = List.of();
    private String vibrationEmbeddingModel = "";
    private List<Double> imageEmbedding = List.of();
    private String imageEmbeddingModel = "";
    private Map<String, String> metadata = Map.of();
    private Map<String, String> provenance = Map.of();

    public Builder id(String v) { id = v; return this; }
    public Builder modality(String v) { modality = v; return this; }
    public Builder datasetId(String v) { datasetId = v; return this; }
    public Builder text(String v) { text = v == null ? "" : v; return this; }
    public Builder textEmbedding(List<Double> v) { textEmbedding = v == null ? List.of() : List.copyOf(v); return this; }
    public Builder textEmbeddingModel(String v) { textEmbeddingModel = v == null ? "" : v; return this; }
    public Builder textEmbeddingArtifact(String v) { textEmbeddingArtifact = v == null ? "" : v; return this; }
    public Builder vibrationEmbedding(List<Double> v) { vibrationEmbedding = v == null ? List.of() : List.copyOf(v); return this; }
    public Builder vibrationEmbeddingModel(String v) { vibrationEmbeddingModel = v == null ? "" : v; return this; }
    public Builder imageEmbedding(List<Double> v) { imageEmbedding = v == null ? List.of() : List.copyOf(v); return this; }
    public Builder imageEmbeddingModel(String v) { imageEmbeddingModel = v == null ? "" : v; return this; }
    public Builder metadata(Map<String, String> v) { metadata = v == null ? Map.of() : Map.copyOf(v); return this; }
    public Builder provenance(Map<String, String> v) { provenance = v == null ? Map.of() : Map.copyOf(v); return this; }

    public StoredRecord build() {
      if (id == null || modality == null || datasetId == null) {
        throw new IllegalArgumentException("id, modality, and datasetId are required");
      }
      return new StoredRecord(
          id, modality, datasetId, text, textEmbedding, textEmbeddingModel, textEmbeddingArtifact,
          vibrationEmbedding, vibrationEmbeddingModel, imageEmbedding, imageEmbeddingModel,
          metadata, provenance);
    }
  }
}
