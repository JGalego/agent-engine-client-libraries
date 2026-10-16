package com.example.nearmiss;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Loads a local {@code .env} file without overriding variables that are already set. */
public final class EnvFiles {

  private EnvFiles() {}

  public static Map<String, String> merged(Path dotenv) throws IOException {
    Map<String, String> merged = new HashMap<>(System.getenv());
    if (dotenv != null && Files.isRegularFile(dotenv)) {
      for (String line : Files.readAllLines(dotenv)) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
          continue;
        }
        int eq = trimmed.indexOf('=');
        if (eq <= 0) {
          continue;
        }
        String key = trimmed.substring(0, eq).trim();
        String value = unquote(trimmed.substring(eq + 1).trim());
        merged.putIfAbsent(key, value);
      }
    }
    return Map.copyOf(merged);
  }

  private static String unquote(String value) {
    if (value.length() >= 2) {
      char first = value.charAt(0);
      char last = value.charAt(value.length() - 1);
      if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
        return value.substring(1, value.length() - 1);
      }
    }
    return value;
  }
}
