package com.example.embeddingzoo.embed;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class WordPieceTokenizerTest {

  @Test
  void wordPieceSplitsAKnownWordAndWrapsSpecialTokens() {
    WordPieceTokenizer tokenizer = WordPieceTokenizer.fromMap(Map.of(
        "[UNK]", 0,
        "[CLS]", 1,
        "[SEP]", 2,
        "bear", 3,
        "##ing", 4,
        "vibration", 5));
    assertArrayEquals(new long[] {1, 3, 4, 5, 2}, tokenizer.encode("bearing vibration", 20));
  }
}
