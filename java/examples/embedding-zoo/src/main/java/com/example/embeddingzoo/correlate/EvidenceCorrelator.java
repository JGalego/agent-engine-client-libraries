package com.example.embeddingzoo.correlate;

import com.example.embeddingzoo.Datasets;
import com.example.embeddingzoo.Vectors;
import com.example.embeddingzoo.store.StoredRecord;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pairs evidence records using provenance first and embedding similarity second.
 *
 * <p>Vectors from different models are never compared. A high cosine across datasets becomes
 * {@code analogous_pattern} with {@code verified_connection: false}.
 */
public final class EvidenceCorrelator {

  private EvidenceCorrelator() {}

  public static Map<String, Object> report(List<StoredRecord> records, double cutoff) {
    List<Map<String, Object>> pairs = new ArrayList<>();
    int cap = Math.min(records.size(), 12);
    for (int i = 0; i < cap; i++) {
      for (int j = i + 1; j < cap; j++) {
        pairs.add(pair(records.get(i), records.get(j), cutoff));
      }
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("disclaimer", Datasets.ANALOGY_DISCLAIMER);
    out.put("analogy_cutoff", cutoff);
    out.put("records_compared", cap);
    if (records.size() > cap) {
      out.put("truncated", true);
    }
    out.put("pairs", pairs);
    return out;
  }

  static Map<String, Object> pair(StoredRecord left, StoredRecord right, double cutoff) {
    boolean sameDataset = left.datasetId().equals(right.datasetId());
    String leftRecording = left.metadata().get("recording_id");
    String rightRecording = right.metadata().get("recording_id");
    String leftDocument = left.metadata().get("document_url");
    String rightDocument = right.metadata().get("document_url");
    String relationship;
    boolean verified;
    StringBuilder explanation = new StringBuilder();
    if (sameDataset && present(leftRecording) && leftRecording.equals(rightRecording)) {
      relationship = "verified_same_recording";
      verified = true;
      explanation.append("Both records come from snapshot ").append(leftRecording)
          .append(" in dataset ").append(left.datasetId()).append(". ");
      String leftChannel = left.metadata().getOrDefault("channel", "");
      String rightChannel = right.metadata().getOrDefault("channel", "");
      if (!leftChannel.isEmpty() && leftChannel.equals(rightChannel)) {
        explanation.append("They are the same channel of that snapshot. ");
      } else if (!leftChannel.isEmpty() || !rightChannel.isEmpty()) {
        explanation.append("They are different channels of that snapshot. ");
      }
    } else if (sameDataset && present(leftDocument) && leftDocument.equals(rightDocument)) {
      relationship = "verified_same_document";
      verified = true;
      explanation.append("Both records are chunks of ").append(leftDocument)
          .append(" in dataset ").append(left.datasetId()).append(". ");
    } else if (sameDataset) {
      relationship = "verified_same_dataset";
      verified = true;
      explanation.append("Both records were ingested from dataset ").append(left.datasetId())
          .append(". That verifies a shared published dataset. It does not verify that they describe the same event or defect. ");
    } else {
      relationship = null;
      verified = false;
    }

    Double cosine = null;
    String sharedModel = "";
    if (present(left.textEmbeddingModel()) && left.textEmbeddingModel().equals(right.textEmbeddingModel())) {
      double score = Vectors.cosine(left.textEmbedding(), right.textEmbedding());
      if (!Double.isNaN(score)) {
        cosine = score;
        sharedModel = left.textEmbeddingModel();
      }
    }
    if (!sameDataset) {
      if (cosine == null) {
        relationship = "not_comparable";
        explanation.append("The records are from datasets ")
            .append(left.datasetId()).append(" and ").append(right.datasetId())
            .append(" and do not share a text embedding model, so no similarity score is reported. ");
      } else if (cosine >= cutoff) {
        relationship = "analogous_pattern";
        explanation.append("Cosine similarity in ").append(sharedModel).append(" is ")
            .append(format(cosine)).append(", at or above the reporting cutoff ").append(format(cutoff))
            .append(". This is an analogous pattern in that embedding space. The datasets were collected independently. This is not a verified connection. ");
      } else {
        relationship = "independent";
        explanation.append("Cosine similarity in ").append(sharedModel).append(" is ")
            .append(format(cosine)).append(", below the reporting cutoff ").append(format(cutoff))
            .append(". The datasets were collected independently. This is not a verified connection. ");
      }
    } else if (cosine != null) {
      explanation.append("Text-embedding cosine in ").append(sharedModel).append(" is ")
          .append(format(cosine)).append(". The score does not add a causal claim. ");
    }

    Double vibration = null;
    if (present(left.modelFor("vibration"))
        && left.modelFor("vibration").equals(right.modelFor("vibration"))
        && left.vector("vibration") != null
        && right.vector("vibration") != null) {
      double score = Vectors.cosine(left.vector("vibration"), right.vector("vibration"));
      if (!Double.isNaN(score)) {
        vibration = score;
        explanation.append("Vibration-feature cosine in ").append(left.modelFor("vibration"))
            .append(" is ").append(format(score)).append(". It is only comparable because both records use that model. ");
      }
    }

    String publisher = left.metadata().get("publisher");
    if (!sameDataset && present(publisher) && publisher.equals(right.metadata().get("publisher"))) {
      explanation.append("Both records name the publisher ").append(publisher)
          .append(". That is a shared catalog attribution, not evidence they describe the same event. ");
    }

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("left_id", left.id());
    out.put("right_id", right.id());
    out.put("left_dataset_id", left.datasetId());
    out.put("right_dataset_id", right.datasetId());
    out.put("left_modality", left.modality());
    out.put("right_modality", right.modality());
    out.put("relationship", relationship);
    out.put("verified_connection", verified);
    if (cosine != null) {
      out.put("text_cosine", cosine);
      out.put("text_embedding_model", sharedModel);
    }
    if (vibration != null) {
      out.put("vibration_cosine", vibration);
    }
    out.put("explanation", explanation.toString().trim());
    return out;
  }

  private static boolean present(String value) {
    return value != null && !value.isBlank();
  }

  private static String format(double value) {
    return String.format(Locale.ROOT, "%.4f", value);
  }
}
