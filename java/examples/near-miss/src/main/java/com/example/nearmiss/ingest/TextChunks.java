package com.example.nearmiss.ingest;

import java.util.ArrayList;
import java.util.List;

/** Splits a narrative into overlapping word windows that fit the embedding model's input. */
public final class TextChunks {

  private TextChunks() {}

  /**
   * @param words words per chunk. MiniLM reads 256 wordpieces, and a word is usually one to two.
   * @param overlap words shared by neighbouring chunks
   * @param maxChunks stops after this many chunks, so one long report cannot dominate the index
   */
  public static List<String> split(String text, int words, int overlap, int maxChunks) {
    String[] tokens = text.trim().split("\\s+");
    if (tokens.length == 0 || tokens[0].isEmpty()) {
      return List.of();
    }
    if (tokens.length <= words) {
      return List.of(String.join(" ", tokens));
    }
    int step = Math.max(1, words - overlap);
    List<String> out = new ArrayList<>();
    for (int start = 0; start < tokens.length && out.size() < maxChunks; start += step) {
      int end = Math.min(tokens.length, start + words);
      out.add(String.join(" ", java.util.Arrays.copyOfRange(tokens, start, end)));
      if (end == tokens.length) {
        break;
      }
    }
    return out;
  }
}
