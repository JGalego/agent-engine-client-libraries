package com.mongodb.agentengine.sdk.memory;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TagSyntaxTest {

  private static String tagError(Map<String, Object> tags) {
    return assertThrows(MemoryClientException.class, () -> TagSyntax.validateTagSyntax(tags)).getMessage();
  }

  private static String typeError(String type) {
    return assertThrows(MemoryClientException.class, () -> TagSyntax.validateMemoryType(type)).getMessage();
  }

  @Test
  void acceptsDeclarableTypeNames() {
    assertDoesNotThrow(() -> TagSyntax.validateMemoryType("support_ticket"));
    assertDoesNotThrow(() -> TagSyntax.validateMemoryType("a" + "b".repeat(63)));
  }

  @Test
  void rejectsBuiltInAndMalformedTypeNames() {
    assertEquals("'semantic' is a built-in memory type and is not accepted by this operation", typeError("semantic"));
    assertEquals("memoryType must be a non-empty string", typeError(" "));
    assertEquals("memoryType must be a non-empty string", typeError(null));
    assertEquals("custom type name 'Note' must match ^[a-z][a-z0-9_]{0,63}$", typeError("Note"));
    assertEquals("custom type name '1x' must match ^[a-z][a-z0-9_]{0,63}$", typeError("1x"));
    typeError("a" + "b".repeat(64));
  }

  @Test
  void acceptsScalarsAndOneLevelOfGroups() {
    assertDoesNotThrow(
        () -> TagSyntax.validateTagSyntax(Map.of("team", "a", "priority", 2, "open", true, "meta", Map.of("env", "prod"))));
    assertDoesNotThrow(() -> TagSyntax.validateTagSyntax(Map.of("dotted.key", "v")));
  }

  @Test
  void rejectsBlankStrings() {
    assertEquals("tag 'team' must have a non-empty string value", tagError(Map.of("team", " ")));
  }

  @Test
  void rejectsNonFiniteNumbers() {
    assertEquals("tag 'n' must be a finite number", tagError(Map.of("n", Double.NaN)));
    assertEquals("tag 'n' must be a finite number", tagError(Map.of("n", Double.POSITIVE_INFINITY)));
  }

  @Test
  void rejectsOtherValueTypes() {
    assertEquals("tag 'l' must be a non-empty string, number, or boolean", tagError(Map.of("l", List.of("a"))));
  }

  @Test
  void rejectsEmptyPathSegmentsAndDeepNesting() {
    assertEquals("tag 'a..b' must have a non-empty path", tagError(Map.of("a..b", "v")));
    assertEquals("tag 'a.b.c': nesting is at most one level deep", tagError(Map.of("a.b.c", "v")));
    assertEquals("tag 'g': nesting is at most one level deep", tagError(Map.of("g", Map.of("x", Map.of("y", "v")))));
    assertEquals("tag 'g': nesting is at most one level deep", tagError(Map.of("g", Map.of("x.y", "v"))));
    assertEquals("tag 'g.h': nesting is at most one level deep", tagError(Map.of("g.h", Map.of("x", "v"))));
  }

  @Test
  void rejectsEmptyGroups() {
    assertEquals("tag group 'g' must contain at least one key", tagError(new LinkedHashMap<>(Map.of("g", Map.of()))));
  }
}
