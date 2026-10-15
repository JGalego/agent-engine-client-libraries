package com.mongodb.agentengine.sdk.clients;

import com.mongodb.agentengine.sdk.Json;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.StringJoiner;

/** Request plumbing shared by the clients. */
final class Http {

  /** A fully read response. */
  record Response(int status, byte[] body) {

    boolean ok() {
      return status >= 200 && status < 300;
    }

    String text() {
      return new String(body, StandardCharsets.UTF_8);
    }

    <T> T parse(Class<T> type) {
      try {
        return Json.mapper().readValue(body, type);
      } catch (IOException e) {
        throw new ResponseFormatException("Unexpected response body: " + e.getMessage(), e);
      }
    }

    void requireOk() {
      if (!ok()) {
        throw new HttpStatusException(status, text());
      }
    }
  }

  private Http() {}

  /** Sends one request. Transport failures surface as {@link UncheckedIOException}. */
  static Response send(
      HttpClient client,
      String method,
      URI uri,
      Map<String, String> headers,
      byte[] body,
      Duration timeout) {
    HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(timeout);
    headers.forEach(request::header);
    request.method(
        method,
        body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
    try {
      HttpResponse<byte[]> response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
      return new Response(response.statusCode(), response.body());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new UncheckedIOException(new InterruptedIOException("Request interrupted"));
    }
  }

  /** Encodes query parameters, skipping null values. */
  static String query(Map<String, String> params) {
    StringJoiner joined = new StringJoiner("&");
    params.forEach(
        (key, value) -> {
          if (value != null) {
            joined.add(
                URLEncoder.encode(key, StandardCharsets.UTF_8)
                    + "="
                    + URLEncoder.encode(value, StandardCharsets.UTF_8));
          }
        });
    return joined.toString();
  }

  /** Appends encoded query parameters to a URL, if there are any. */
  static URI withQuery(String url, Map<String, String> params) {
    String query = query(params);
    return URI.create(query.isEmpty() ? url : url + "?" + query);
  }

  static String trimTrailingSlash(String url) {
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }
}
