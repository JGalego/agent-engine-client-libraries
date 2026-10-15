package com.example.embeddingzoo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SamplesTest {

  @Test
  void selectionKeepsBothEnds() {
    List<Integer> indexes = Samples.indexes(100, 10, true);
    assertEquals(0, indexes.get(0));
    assertEquals(99, indexes.get(indexes.size() - 1));
    assertTrue(indexes.size() <= 10);
    assertEquals(indexes.stream().sorted().toList(), indexes);
  }

  @Test
  void shortInputIsCopied() {
    assertEquals(List.of(0, 1, 2), Samples.indexes(3, 10, false));
  }
}
