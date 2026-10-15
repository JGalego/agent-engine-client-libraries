package com.example.embeddingzoo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/** Picks a documented subset of an ordered collection, always keeping the ends. */
public final class Samples {

  private Samples() {}

  /**
   * Indexes covering {@code size} items.
   *
   * @param denseTail when true, keep extra indexes at the end before filling the middle. Used for
   *     run-to-failure recordings whose last snapshots are the ones the dataset documentation
   *     describes.
   */
  public static List<Integer> indexes(int size, int max, boolean denseTail) {
    if (size <= 0) {
      return List.of();
    }
    if (max <= 0 || size <= max) {
      List<Integer> all = new ArrayList<>(size);
      for (int i = 0; i < size; i++) {
        all.add(i);
      }
      return all;
    }
    LinkedHashSet<Integer> chosen = new LinkedHashSet<>();
    chosen.add(0);
    chosen.add(size - 1);
    if (denseTail) {
      int tail = Math.min(Math.max(1, max / 4), size);
      for (int i = 0; i < tail; i++) {
        chosen.add(size - 1 - i);
      }
    }
    int slots = Math.max(0, max - chosen.size());
    for (int slot = 0; slot < slots; slot++) {
      int index = (int) Math.round(slot * (size - 1) / (double) Math.max(1, slots));
      chosen.add(index);
    }
    for (int i = 0; chosen.size() < max && i < size; i++) {
      chosen.add(i);
    }
    List<Integer> ordered = new ArrayList<>(chosen);
    Collections.sort(ordered);
    while (ordered.size() > max) {
      int middle = ordered.size() / 2;
      if (ordered.get(middle) == 0 || ordered.get(middle) == size - 1) {
        break;
      }
      ordered.remove(middle);
    }
    return List.copyOf(ordered);
  }

  public static <T> List<T> select(List<T> ordered, int max, boolean denseTail) {
    List<Integer> indexes = indexes(ordered.size(), max, denseTail);
    List<T> out = new ArrayList<>(indexes.size());
    for (int index : indexes) {
      out.add(ordered.get(index));
    }
    return List.copyOf(out);
  }
}
