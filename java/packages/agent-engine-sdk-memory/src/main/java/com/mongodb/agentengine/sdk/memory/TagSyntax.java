package com.mongodb.agentengine.sdk.memory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Syntax-only client-side checks for custom-type names and tag payloads.
 *
 * <p>They mirror the shape rules of the memory server's tag canonicalization with identical
 * messages, so a fail-fast client error reads like a server rejection. Server-owned rules are
 * deliberately not mirrored (the int64 range guard, dotted-plus-nested mixed forms, operator
 * expressions, key-count caps, declaration membership): those values reach the platform intact, so
 * the server's own rejection is the one the caller sees.
 *
 * <p>Non-finite numbers are the exception. JSON has no encoding for them, so they would never
 * reach the server to be judged; rejecting them here is what makes the server's rule reachable.
 */
final class TagSyntax {

  /** Built-in type names, which dedicated methods handle. */
  static final Set<String> RESERVED_TYPE_NAMES =
      Set.of(
          "semantic", "episodic", "taxonomic", "procedural", "snapshot", "entity", "preferences",
          "turn", "stm", "short_term");

  private static final int MAX_TAG_KEY_DEPTH = 2;

  /**
   * The name pattern custom types must follow. A name that cannot be declared can never exist, so
   * checking it here turns an unroutable request into a precise local error.
   */
  private static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");

  private TagSyntax() {}

  static void validateMemoryType(String memoryType) {
    if (memoryType == null || memoryType.isBlank()) {
      throw new MemoryClientException("memoryType must be a non-empty string");
    }
    if (RESERVED_TYPE_NAMES.contains(memoryType)) {
      throw new MemoryClientException(
          "'" + memoryType + "' is a built-in memory type and is not accepted by this operation");
    }
    if (!NAME.matcher(memoryType).matches()) {
      throw new MemoryClientException(
          "custom type name '" + memoryType + "' must match " + NAME.pattern());
    }
  }

  static void validateTagSyntax(Map<String, Object> tags) {
    flatten(tags)
        .forEach(
            (path, value) -> {
              checkPath(path);
              checkScalar(path, value);
            });
  }

  private static void checkPath(String path) {
    String[] segments = path.split("\\.", -1);
    for (String segment : segments) {
      if (segment.isEmpty()) {
        throw new MemoryClientException("tag '" + path + "' must have a non-empty path");
      }
    }
    if (segments.length > MAX_TAG_KEY_DEPTH) {
      throw new MemoryClientException("tag '" + path + "': nesting is at most one level deep");
    }
  }

  private static void checkScalar(String path, Object value) {
    if (value instanceof String s) {
      if (s.isBlank()) {
        throw new MemoryClientException("tag '" + path + "' must have a non-empty string value");
      }
    } else if (value instanceof Number n) {
      if (Double.isNaN(n.doubleValue()) || Double.isInfinite(n.doubleValue())) {
        throw new MemoryClientException("tag '" + path + "' must be a finite number");
      }
    } else if (!(value instanceof Boolean)) {
      throw new MemoryClientException(
          "tag '" + path + "' must be a non-empty string, number, or boolean");
    }
  }

  /**
   * Flattens either accepted wire form to dotted paths. Group-level rules apply here, so the
   * per-leaf checks that follow see one uniform sequence.
   */
  private static Map<String, Object> flatten(Map<String, Object> tags) {
    Map<String, Object> flat = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : tags.entrySet()) {
      String key = entry.getKey();
      if (!(entry.getValue() instanceof Map<?, ?> group)) {
        flat.put(key, entry.getValue());
        continue;
      }
      if (key.contains(".")) {
        throw new MemoryClientException("tag '" + key + "': nesting is at most one level deep");
      }
      if (group.isEmpty()) {
        throw new MemoryClientException("tag group '" + key + "' must contain at least one key");
      }
      for (Map.Entry<?, ?> sub : group.entrySet()) {
        String subKey = String.valueOf(sub.getKey());
        if (subKey.contains(".") || sub.getValue() instanceof Map) {
          throw new MemoryClientException("tag '" + key + "': nesting is at most one level deep");
        }
        flat.put(key + "." + subKey, sub.getValue());
      }
    }
    return flat;
  }
}
