package com.example.nearmiss;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Vector and digest helpers shared by embedders, search, and manifests. */
public final class Vectors {

  private Vectors() {}

  /** L2-normalizes {@code values}. A zero vector stays zero. */
  public static List<Double> normalize(double[] values) {
    double sum = 0;
    for (double value : values) {
      sum += value * value;
    }
    double norm = Math.sqrt(sum);
    List<Double> out = new ArrayList<>(values.length);
    if (norm < 1e-12) {
      for (int i = 0; i < values.length; i++) {
        out.add(0.0);
      }
      return List.copyOf(out);
    }
    for (double value : values) {
      out.add(value / norm);
    }
    return List.copyOf(out);
  }

  public static List<Double> box(float[] values) {
    List<Double> out = new ArrayList<>(values.length);
    for (float value : values) {
      out.add((double) value);
    }
    return List.copyOf(out);
  }

  /** Cosine similarity, or {@link Double#NaN} when the vectors cannot be compared. */
  public static double cosine(List<Double> left, List<Double> right) {
    if (left == null || right == null || left.isEmpty() || left.size() != right.size()) {
      return Double.NaN;
    }
    double dot = 0;
    double leftNorm = 0;
    double rightNorm = 0;
    for (int i = 0; i < left.size(); i++) {
      double x = left.get(i);
      double y = right.get(i);
      dot += x * y;
      leftNorm += x * x;
      rightNorm += y * y;
    }
    if (leftNorm < 1e-12 || rightNorm < 1e-12) {
      return 0;
    }
    return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
  }

  public static String sha256(byte[] bytes) {
    return hex(digest().digest(bytes));
  }

  public static String sha256(String text) {
    return sha256(text.getBytes(StandardCharsets.UTF_8));
  }

  public static String sha256(Path path) throws IOException {
    MessageDigest digest = digest();
    try (InputStream in = Files.newInputStream(path)) {
      byte[] buffer = new byte[1 << 20];
      int read;
      while ((read = in.read(buffer)) > 0) {
        digest.update(buffer, 0, read);
      }
    }
    return hex(digest.digest());
  }

  public static String idFor(String datasetId, String locator, String modelId) {
    return sha256(datasetId + "\n" + locator + "\n" + modelId);
  }

  private static MessageDigest digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String hex(byte[] bytes) {
    return HexFormat.of().formatHex(bytes);
  }
}
