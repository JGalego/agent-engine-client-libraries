package com.mongodb.agentengine.sdk.clients;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PathSegmentsTest {

  @Test
  void encodesReservedCharactersAndSpaces() {
    assertEquals("a%2Fb%20c%3Fd%23e", PathSegments.encode("a/b c?d#e"));
  }

  @Test
  void leavesPlainIdsAlone() {
    assertEquals("thread_1-A.b", PathSegments.encode("thread_1-A.b"));
  }

  @Test
  void refusesBareDotSegments() {
    assertThrows(IllegalArgumentException.class, () -> PathSegments.encode("."));
    assertThrows(IllegalArgumentException.class, () -> PathSegments.encode(".."));
  }
}
