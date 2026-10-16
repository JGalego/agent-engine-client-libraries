package com.example.nearmiss.embed;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * BERT WordPiece tokenizer for the uncased MiniLM vocabulary.
 *
 * <p>It lowercases, strips combining marks, splits punctuation, then applies the WordPiece rules
 * used by {@code bert-base-uncased}. Inputs are truncated to {@code maxLength} including
 * {@code [CLS]} and {@code [SEP]}.
 */
public final class WordPieceTokenizer {

  private final Map<String, Integer> vocab;
  private final int unknown;
  private final int cls;
  private final int sep;

  private WordPieceTokenizer(Map<String, Integer> vocab) {
    this.vocab = vocab;
    this.unknown = vocab.getOrDefault("[UNK]", 100);
    this.cls = vocab.getOrDefault("[CLS]", 101);
    this.sep = vocab.getOrDefault("[SEP]", 102);
  }

  public static WordPieceTokenizer fromVocabFile(Path vocabFile) throws IOException {
    Map<String, Integer> vocab = new HashMap<>();
    int id = 0;
    for (String line : Files.readAllLines(vocabFile)) {
      if (!line.isEmpty()) {
        vocab.put(line, id);
        id++;
      }
    }
    if (!vocab.containsKey("[CLS]") || !vocab.containsKey("[SEP]")) {
      throw new IOException("Vocabulary is missing [CLS] or [SEP]: " + vocabFile);
    }
    return new WordPieceTokenizer(vocab);
  }

  /** Visible for tests that supply a tiny vocabulary. */
  public static WordPieceTokenizer fromMap(Map<String, Integer> vocab) {
    return new WordPieceTokenizer(Map.copyOf(vocab));
  }

  public long[] encode(String text, int maxLength) {
    if (maxLength < 2) {
      throw new IllegalArgumentException("maxLength must leave room for [CLS] and [SEP]");
    }
    List<Integer> ids = new ArrayList<>();
    ids.add(cls);
    for (String word : basicTokens(text)) {
      for (String piece : wordPiece(word)) {
        if (ids.size() == maxLength - 1) {
          ids.add(sep);
          return toArray(ids);
        }
        ids.add(vocab.getOrDefault(piece, unknown));
      }
    }
    ids.add(sep);
    return toArray(ids);
  }

  private List<String> wordPiece(String token) {
    if (token.length() > 100) {
      return List.of("[UNK]");
    }
    List<String> pieces = new ArrayList<>();
    int start = 0;
    while (start < token.length()) {
      int end = token.length();
      String current = null;
      while (start < end) {
        String slice = token.substring(start, end);
        if (start > 0) {
          slice = "##" + slice;
        }
        if (vocab.containsKey(slice)) {
          current = slice;
          break;
        }
        end--;
      }
      if (current == null) {
        return List.of("[UNK]");
      }
      pieces.add(current);
      start = end;
    }
    return pieces;
  }

  static List<String> basicTokens(String text) {
    String cleaned = clean(text).toLowerCase(Locale.ROOT);
    cleaned = stripMarks(Normalizer.normalize(cleaned, Normalizer.Form.NFD));
    List<String> tokens = new ArrayList<>();
    for (String word : cleaned.split("\\s+")) {
      if (word.isEmpty()) {
        continue;
      }
      tokens.addAll(splitPunctuation(word));
    }
    return tokens;
  }

  private static String clean(String text) {
    StringBuilder out = new StringBuilder(text.length());
    for (int offset = 0; offset < text.length(); ) {
      int codePoint = text.codePointAt(offset);
      offset += Character.charCount(codePoint);
      if (codePoint == 0 || codePoint == 0xFFFD || isControl(codePoint)) {
        continue;
      }
      if (Character.isWhitespace(codePoint)) {
        out.append(' ');
      } else {
        out.appendCodePoint(codePoint);
      }
    }
    return out.toString();
  }

  private static String stripMarks(String text) {
    StringBuilder out = new StringBuilder(text.length());
    for (int offset = 0; offset < text.length(); ) {
      int codePoint = text.codePointAt(offset);
      offset += Character.charCount(codePoint);
      if (Character.getType(codePoint) != Character.NON_SPACING_MARK) {
        out.appendCodePoint(codePoint);
      }
    }
    return out.toString();
  }

  private static List<String> splitPunctuation(String word) {
    List<String> tokens = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    for (int offset = 0; offset < word.length(); ) {
      int codePoint = word.codePointAt(offset);
      offset += Character.charCount(codePoint);
      if (isPunctuation(codePoint)) {
        if (!current.isEmpty()) {
          tokens.add(current.toString());
          current.setLength(0);
        }
        tokens.add(Character.toString(codePoint));
      } else {
        current.appendCodePoint(codePoint);
      }
    }
    if (!current.isEmpty()) {
      tokens.add(current.toString());
    }
    return tokens;
  }

  private static boolean isControl(int codePoint) {
    if (codePoint == '\t' || codePoint == '\n' || codePoint == '\r') {
      return false;
    }
    return Character.isISOControl(codePoint);
  }

  private static boolean isPunctuation(int codePoint) {
    if ((codePoint >= 33 && codePoint <= 47)
        || (codePoint >= 58 && codePoint <= 64)
        || (codePoint >= 91 && codePoint <= 96)
        || (codePoint >= 123 && codePoint <= 126)) {
      return true;
    }
    int type = Character.getType(codePoint);
    return type == Character.CONNECTOR_PUNCTUATION
        || type == Character.DASH_PUNCTUATION
        || type == Character.START_PUNCTUATION
        || type == Character.END_PUNCTUATION
        || type == Character.INITIAL_QUOTE_PUNCTUATION
        || type == Character.FINAL_QUOTE_PUNCTUATION
        || type == Character.OTHER_PUNCTUATION;
  }

  private static long[] toArray(List<Integer> ids) {
    long[] out = new long[ids.size()];
    for (int i = 0; i < ids.size(); i++) {
      out[i] = ids.get(i);
    }
    return out;
  }
}
