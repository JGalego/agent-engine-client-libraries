package com.mongodb.agentengine.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.agentengine.sdk.Json;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;

/** The runner's HTTP client for the orchestration engine. */
public class OeClient {

  /** An OE response. */
  public record Response(int status, String body) {

    public boolean ok() {
      return status >= 200 && status < 300;
    }

    /** The body parsed as JSON. */
    public JsonNode json() throws IOException {
      return Json.mapper().readTree(body);
    }
  }

  private static final Set<Integer> RETRYABLE = Set.of(502, 503, 504);

  private final String baseUrl;
  private final Duration timeout;
  private final HttpClient http;

  public OeClient(String baseUrl, Duration timeout) {
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.timeout = timeout;
    // HTTP/1.1 only: on plain http:// the JDK client otherwise attempts an h2c upgrade, and ASGI
    // servers drop the request body.
    this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(timeout).build();
  }

  /**
   * POSTs a JSON body, retrying transient failures (502, 503, 504 and network errors) with
   * exponential backoff.
   *
   * @param attempts total attempts, at least one
   * @throws UncheckedIOException if every attempt failed to reach the OE
   */
  public Response postJson(String path, Object body, int attempts) {
    byte[] bytes;
    try {
      bytes = Json.mapper().writeValueAsBytes(body);
    } catch (IOException e) {
      throw new IllegalArgumentException("Request body is not serializable: " + e.getMessage(), e);
    }
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
            .build();
    for (int attempt = 1; ; attempt++) {
      boolean last = attempt >= attempts;
      try {
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (last || !RETRYABLE.contains(response.statusCode())) {
          return new Response(response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
        }
      } catch (IOException e) {
        if (last) {
          throw new UncheckedIOException(e);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new UncheckedIOException(new InterruptedIOException("Interrupted while calling the OE"));
      }
      try {
        Thread.sleep(200L << (attempt - 1));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new UncheckedIOException(new InterruptedIOException("Interrupted while retrying"));
      }
    }
  }
}
