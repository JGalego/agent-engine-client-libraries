package com.example.embeddingzoo.signal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads one NASA IMS snapshot: whitespace-separated samples, one row per time step. */
public final class ImsSignalFile {

  private static final Pattern STAMP =
      Pattern.compile("(\\d{4})\\.(\\d{2})\\.(\\d{2})\\.(\\d{2})\\.(\\d{2})\\.(\\d{2})");

  private ImsSignalFile() {}

  /** Returns {@code channels[channel][sample]}. */
  public static double[][] readChannels(Path path) throws IOException {
    byte[] bytes = Files.readAllBytes(path);
    int columns = columnsOf(bytes);
    int numbers = countNumbers(bytes);
    if (columns == 0 || numbers == 0 || numbers % columns != 0) {
      throw new IOException("IMS snapshot " + path.getFileName() + " does not have a rectangular sample grid");
    }
    int rows = numbers / columns;
    double[][] channels = new double[columns][rows];
    int index = 0;
    int row = 0;
    int column = 0;
    while (index < bytes.length) {
      while (index < bytes.length && isSpace(bytes[index])) {
        index++;
      }
      if (index >= bytes.length) {
        break;
      }
      int start = index;
      while (index < bytes.length && !isSpace(bytes[index])) {
        index++;
      }
      double value = Double.parseDouble(new String(bytes, start, index - start, StandardCharsets.US_ASCII));
      channels[column][row] = value;
      column++;
      if (column == columns) {
        column = 0;
        row++;
      }
    }
    if (row != rows || column != 0) {
      throw new IOException("Stopped early while reading " + path.getFileName());
    }
    return channels;
  }

  /** Timestamp text printed in the filename, without an invented timezone. */
  public static Optional<String> timestampLabel(String filename) {
    Matcher matcher = STAMP.matcher(filename);
    if (!matcher.find()) {
      return Optional.empty();
    }
    return Optional.of(
        matcher.group(1) + "-" + matcher.group(2) + "-" + matcher.group(3)
            + "T" + matcher.group(4) + ":" + matcher.group(5) + ":" + matcher.group(6));
  }

  public static boolean snapshotName(String filename) {
    return STAMP.matcher(filename).find();
  }

  private static int columnsOf(byte[] bytes) {
    int index = 0;
    while (index < bytes.length && (bytes[index] == '\n' || bytes[index] == '\r')) {
      index++;
    }
    int columns = 0;
    while (index < bytes.length && bytes[index] != '\n' && bytes[index] != '\r') {
      while (index < bytes.length && isHorizontalSpace(bytes[index])) {
        index++;
      }
      if (index >= bytes.length || bytes[index] == '\n' || bytes[index] == '\r') {
        break;
      }
      columns++;
      while (index < bytes.length && !isSpace(bytes[index])) {
        index++;
      }
    }
    return columns;
  }

  private static int countNumbers(byte[] bytes) {
    int count = 0;
    int index = 0;
    while (index < bytes.length) {
      while (index < bytes.length && isSpace(bytes[index])) {
        index++;
      }
      if (index >= bytes.length) {
        break;
      }
      count++;
      while (index < bytes.length && !isSpace(bytes[index])) {
        index++;
      }
    }
    return count;
  }

  private static boolean isSpace(byte value) {
    return value == ' ' || value == '\t' || value == '\n' || value == '\r' || value == '\f';
  }

  private static boolean isHorizontalSpace(byte value) {
    return value == ' ' || value == '\t' || value == '\f';
  }
}
