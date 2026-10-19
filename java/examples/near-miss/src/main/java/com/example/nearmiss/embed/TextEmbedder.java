package com.example.nearmiss.embed;

import java.util.ArrayList;
import java.util.List;

/**
 * Encodes text into one L2-normalized embedding.
 *
 * <p>{@link #embed} is for the stored records and {@link #embedQuery} for a search query. Models that embed both
 * the same way, like MiniLM, only implement {@link #embed}.
 */
public interface TextEmbedder {

  String modelId();

  String artifact();

  int dimensions();

  List<Double> embed(String text);

  default List<Double> embedQuery(String text) {
    return embed(text);
  }

  default List<List<Double>> embedAll(List<String> texts) {
    List<List<Double>> out = new ArrayList<>(texts.size());
    for (String text : texts) {
      out.add(embed(text));
    }
    return out;
  }
}
