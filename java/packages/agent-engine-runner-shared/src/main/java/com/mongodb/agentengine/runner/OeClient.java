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
import java.util.Map;
import java.util.Set;
import javax.net.ssl.SSLContext;

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

  /** Receives the {@code data} payload of each server-sent event. */
  @FunctionalInterface
  public interface EventHandler {

    /** Handles one event; return false to stop reading the stream. */
    boolean accept(String data) throws IOException;
  }

  private static final Set<Integer> RETRYABLE = Set.of(502, 503, 504);

  private final String baseUrl;
  private final Duration timeout;
  private final HttpClient http;

  public OeClient(String baseUrl, Duration timeout) {
    this(baseUrl, timeout, null);
  }

  /**
   * @param tls the mutual-TLS context for an https OE, or null for the JVM's default
   */
  public OeClient(String baseUrl, Duration timeout, SSLContext tls) {
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.timeout = timeout;
    // HTTP/1.1 only: on plain http:// the JDK client otherwise attempts an h2c upgrade, and ASGI
    // servers drop the request body.
    HttpClient.Builder builder = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(timeout);
    if (tls != null) {
      builder.sslContext(tls);
    }
    this.http = builder.build();
  }

  /**
   * A client for the OE named by {@code OE_URL}. An https URL needs the platform's TLS material in
   * {@code env}; see {@link PlatformTls}.
   */
  public static OeClient fromEnvironment(String baseUrl, Duration timeout, Map<String, String> env) {
    boolean https = baseUrl.regionMatches(true, 0, "https://", 0, "https://".length());
    return new OeClient(baseUrl, timeout, https ? PlatformTls.fromEnvironment(env) : null);
  }

  /**
   * POSTs a JSON body, retrying transient failures (502, 503, 504 and network errors) with
   * exponential backoff.
   *
   * @param attempts total attempts, at least one
   * @throws UncheckedIOException if every attempt failed to reach the OE
   */
  public Response postJson(String path, Object body, int attempts) {
    return postJson(path, body, attempts, timeout);
  }

  /**
   * Like {@link #postJson(String, Object, int)} with its own timeout, for calls the OE holds open
   * while a tool or model runs.
   */
  public Response postJson(String path, Object body, int attempts, Duration callTimeout) {
    byte[] bytes;
    try {
      bytes = Json.mapper().writeValueAsBytes(body);
    } catch (IOException e) {
      throw new IllegalArgumentException("Request body is not serializable: " + e.getMessage(), e);
    }
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(callTimeout)
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

  /**
   * POSTs to a stream URL the OE handed out and reads its server-sent events.
   *
   * <p>The URL must be on this client's OE: a stream URL is authoritative for what the runtime treats
   * as model output, so a foreign one is refused rather than followed. A relative path is resolved
   * against the OE.
   *
   * @return the HTTP status; the handler runs only for a 2xx response
   * @throws IllegalArgumentException if the URL is not on the OE
   * @throws UncheckedIOException on a network failure
   */
  public int postEvents(String streamUrl, Duration callTimeout, EventHandler handler) {
    String url = streamUrl.startsWith("/") ? baseUrl + streamUrl : streamUrl;
    if (!url.equals(baseUrl) && !url.startsWith(baseUrl + "/")) {
      throw new IllegalArgumentException("Refusing a stream URL outside the orchestration engine: " + streamUrl);
    }
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(callTimeout)
            .header("Accept", "text/event-stream")
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
    try {
      HttpResponse<java.util.stream.Stream<String>> response = http.send(request, HttpResponse.BodyHandlers.ofLines());
      try (java.util.stream.Stream<String> lines = response.body()) {
        if (response.statusCode() / 100 != 2) {
          return response.statusCode();
        }
        StringBuilder data = new StringBuilder();
        java.util.Iterator<String> iterator = lines.iterator();
        while (iterator.hasNext()) {
          String line = iterator.next();
          if (line.isEmpty()) {
            if (data.length() > 0 && !handler.accept(data.toString())) {
              return response.statusCode();
            }
            data.setLength(0);
          } else if (line.startsWith("data:")) {
            if (data.length() > 0) {
              data.append('\n');
            }
            data.append(line.substring(5).stripLeading());
          }
        }
        if (data.length() > 0) {
          handler.accept(data.toString());
        }
        return response.statusCode();
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new UncheckedIOException(new InterruptedIOException("Interrupted while reading the stream"));
    }
  }
}
