package com.example.nearmiss.embed;

import com.example.nearmiss.NearMissConfig;
import com.example.nearmiss.Vectors;
import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.agentengine.sdk.Json;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Voyage AI {@code /v1/embeddings}, by default through Atlas ({@code https://ai.mongodb.com/v1}) with a model API
 * key created in Atlas. Used when {@code NEAR_MISS_TEXT_PROVIDER=voyage}.
 *
 * <p>Stored records are sent as {@code input_type=document} and search queries as {@code input_type=query}, which
 * is what the Voyage docs ask for in retrieval.
 */
public final class VoyageTextEmbedder implements TextEmbedder {

  private final HttpClient http;
  private final String key;
  private final String baseUrl;
  private final String model;
  private volatile int dimensions = -1;

  public VoyageTextEmbedder(NearMissConfig config) {
    this(config.voyageKey(), config.voyageBaseUrl(), config.voyageModel());
  }

  VoyageTextEmbedder(String key, String baseUrl, String model) {
    if (key == null) {
      throw new IllegalStateException("NEAR_MISS_TEXT_PROVIDER=voyage requires VOYAGE_API_KEY");
    }
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    this.key = key;
    this.baseUrl = baseUrl.replaceAll("/$", "");
    this.model = model;
  }

  @Override
  public String modelId() {
    return "voyage/" + model;
  }

  @Override
  public String artifact() {
    return baseUrl + "/embeddings model=" + model;
  }

  @Override
  public int dimensions() {
    if (dimensions < 0) {
      embed("dimension probe");
    }
    return dimensions;
  }

  @Override
  public List<Double> embed(String text) {
    return embedAll(List.of(text == null ? "" : text)).get(0);
  }

  @Override
  public List<Double> embedQuery(String text) {
    return request(List.of(text == null ? "" : text), "query").get(0);
  }

  @Override
  public List<List<Double>> embedAll(List<String> texts) {
    return request(texts, "document");
  }

  private List<List<Double>> request(List<String> texts, String inputType) {
    try {
      String body = Json.mapper().createObjectNode()
          .put("model", model)
          .put("input_type", inputType)
          .set("input", Json.mapper().valueToTree(texts))
          .toString();
      HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/embeddings"))
          .timeout(Duration.ofSeconds(60))
          .header("Authorization", "Bearer " + key)
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body))
          .build();
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= 400) {
        throw new IllegalStateException("Voyage embeddings HTTP " + response.statusCode() + ": " + response.body());
      }
      JsonNode data = Json.mapper().readTree(response.body()).path("data");
      List<List<Double>> ordered = new ArrayList<>();
      for (int i = 0; i < texts.size(); i++) {
        ordered.add(null);
      }
      for (JsonNode item : data) {
        double[] vector = new double[item.path("embedding").size()];
        for (int i = 0; i < vector.length; i++) {
          vector[i] = item.path("embedding").path(i).asDouble();
        }
        dimensions = vector.length;
        ordered.set(item.path("index").asInt(), Vectors.normalize(vector));
      }
      for (List<Double> vector : ordered) {
        if (vector == null) {
          throw new IllegalStateException("Voyage embeddings response was missing a vector");
        }
      }
      return ordered;
    } catch (IOException e) {
      throw new IllegalStateException("Voyage embeddings request failed", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Voyage embeddings request was interrupted", e);
    }
  }
}
