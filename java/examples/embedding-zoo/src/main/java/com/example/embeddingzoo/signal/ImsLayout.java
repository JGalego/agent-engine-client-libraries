package com.example.embeddingzoo.signal;

import java.util.Locale;

/**
 * Channel map from the IMS bearing data sheet distributed with the NASA archive.
 *
 * <p>Test 1 has eight channels, two per bearing. Tests 2 and 3 have four channels, one per bearing.
 * The defect phrases describe the end of a test, from that data sheet. They are not labels of an
 * individual snapshot.
 */
public final class ImsLayout {

  private ImsLayout() {}

  public static int bearing(int columns, int channelIndex) {
    if (columns >= 8) {
      return channelIndex / 2 + 1;
    }
    return channelIndex + 1;
  }

  /** Zero-based index of the first channel listed for this bearing. */
  public static int representativeChannel(int columns, int bearing) {
    if (columns >= 8) {
      return (bearing - 1) * 2;
    }
    return bearing - 1;
  }

  public static String testSet(String path, int columns) {
    String lower = path.toLowerCase(Locale.ROOT);
    if (lower.contains("2nd_test")) {
      return "2";
    }
    if (lower.contains("3rd_test") || lower.contains("4th_test")) {
      return "3";
    }
    if (lower.contains("1st_test") || columns >= 8) {
      return "1";
    }
    return "unknown";
  }

  /** Empty when the data sheet does not name an end-of-test defect for this bearing. */
  public static String testEndNote(String path, int columns, int bearing) {
    String test = testSet(path, columns);
    if ("1".equals(test) && bearing == 3) {
      return "inner race defect at the end of test 1";
    }
    if ("1".equals(test) && bearing == 4) {
      return "roller element defect at the end of test 1";
    }
    if ("2".equals(test) && bearing == 1) {
      return "outer race defect at the end of test 2";
    }
    if ("3".equals(test) && bearing == 3) {
      return "outer race defect at the end of test 3";
    }
    return "";
  }
}
