package com.example.embeddingzoo.embed;

import com.example.embeddingzoo.Vectors;
import com.example.embeddingzoo.ZooConfig;
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

/** OpenAI {@code /v1/embeddings}. Used when {@code EMBEDDING_ZOO_TEXT_PROVIDER=openai}. */
public final class OpenAiTextEmbedder implements TextEmbedder {

  private final HttpClient http;
  private final String key;
  private final String baseUrl;
  private final String model;
  private volatile int dimensions = -1;

  public OpenAiTextEmbedder(ZooConfig config) {
    if (config.openAiKey() == null) {
      throw new IllegalStateException("EMBEDDING_ZOO_TEXT_PROVIDER=openai requires OPENAI_API_KEY");
    }
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    this.key = config.openAiKey();
    this.baseUrl = config.openAiBaseUrl().replaceAll("/$", "");
    this.model = config.openAiEmbeddingModel();
  }

  @Override
  public String modelId() {
    return "openai/" + model;
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
  public List<List<Double>> embedAll(List<String> texts) {
    try {
      String body = Json.mapper().createObjectNode()
          .put("model", model)
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
        throw new IllegalStateException("OpenAI embeddings HTTP " + response.statusCode());
      }
      JsonNode data = Json.mapper().readTree(response.body()).path("data");
      List<List<Double>> ordered = new ArrayList<>();
      for (int i = 0; i < texts.size(); i++) {
        ordered.add(null);
      }
      for (JsonNode item : data) {
        List<Double> vector = new ArrayList<>();
        for (JsonNode number : item.path("embedding")) {
          vector.add(number.asDouble());
        }
        dimensions = vector.size();
        ordered.set(item.path("index").asInt(), Vectors.normalize(unbox(vector)));
      }
      for (List<Double> vector : ordered) {
        if (vector == null) {
          throw new IllegalStateException("OpenAI embeddings response was missing a vector");
        }
      }
      return ordered;
    } catch (IOException e) {
      throw new IllegalStateException("OpenAI embeddings request failed", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("OpenAI embeddings request was interrupted", e);
    }
  }

  private static double[] unbox(List<Double> values) {
    double[] out = new double[values.size()];
    for (int i = 0; i < values.size(); i++) {
      out[i] = values.get(i);
    }
    return out;
  }
}
