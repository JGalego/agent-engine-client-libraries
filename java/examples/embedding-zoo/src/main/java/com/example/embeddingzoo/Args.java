package com.example.embeddingzoo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads tool arguments the orchestration engine passes as JSON objects. */
public final class Args {

  private Args() {}

  public static String str(Map<String, Object> args, String key, String fallback) {
    Object value = args == null ? null : args.get(key);
    if (value == null) {
      return fallback;
    }
    String text = String.valueOf(value).trim();
    return text.isEmpty() ? fallback : text;
  }

  public static int integer(Map<String, Object> args, String key, int fallback) {
    Object value = args == null ? null : args.get(key);
    if (value instanceof Number number) {
      return number.intValue();
    }
    if (value instanceof String text && !text.isBlank()) {
      try {
        return Integer.parseInt(text.trim());
      } catch (NumberFormatException e) {
        return fallback;
      }
    }
    return fallback;
  }

  public static List<String> strings(Map<String, Object> args, String key) {
    Object value = args == null ? null : args.get(key);
    if (value == null) {
      return List.of();
    }
    if (value instanceof String text) {
      return text.isBlank() ? List.of() : List.of(text);
    }
    if (value instanceof List<?> list) {
      List<String> out = new ArrayList<>();
      for (Object item : list) {
        if (item != null && !String.valueOf(item).isBlank()) {
          out.add(String.valueOf(item));
        }
      }
      return List.copyOf(out);
    }
    return List.of(String.valueOf(value));
  }

  public static Map<String, String> stringMap(Map<String, Object> args, String key) {
    Object value = args == null ? null : args.get(key);
    if (!(value instanceof Map<?, ?> map)) {
      return Map.of();
    }
    Map<String, String> out = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      if (entry.getKey() != null && entry.getValue() != null) {
        out.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
      }
    }
    return Map.copyOf(out);
  }
}
