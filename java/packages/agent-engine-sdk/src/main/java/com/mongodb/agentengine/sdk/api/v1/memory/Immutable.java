package com.mongodb.agentengine.sdk.api.v1.memory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Null-tolerant defensive copies for request fields; they keep ordering and allow null values. */
final class Immutable {

  private Immutable() {}

  static <T> List<T> list(List<T> value) {
    return value == null ? null : Collections.unmodifiableList(new ArrayList<>(value));
  }

  static <K, V> Map<K, V> map(Map<K, V> value) {
    return value == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(value));
  }

  static <T> Set<T> set(Set<T> value) {
    return value == null ? null : Collections.unmodifiableSet(new LinkedHashSet<>(value));
  }
}
