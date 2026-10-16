package com.example.nearmiss.ingest;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** The provenance every stored record carries: where the text came from, under what terms, and its hash. */
final class Provenance {

  private Provenance() {}

  static Map<String, String> of(
      String datasetName, String sourceUrl, String license, String licenseUrl, String citation, String locator,
      String contentSha256) {
    Map<String, String> map = new LinkedHashMap<>();
    map.put("dataset_name", datasetName);
    map.put("source_url", sourceUrl);
    map.put("license", license);
    map.put("license_url", licenseUrl);
    map.put("citation", citation);
    map.put("retrieved_at", Instant.now().toString());
    map.put("content_sha256", contentSha256);
    map.put("locator", locator);
    return map;
  }
}
