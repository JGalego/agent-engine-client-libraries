package com.mongodb.agentengine.runner;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** A tiny HTTP/1.1 client for exercising a server under test. */
final class TestHttp {

  record Response(int status, String body) {}

  private static final HttpClient CLIENT = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

  private TestHttp() {}

  static Response get(int port, String path) {
    return send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build());
  }

  static Response post(int port, String path, String json) {
    return send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build());
  }

  private static Response send(HttpRequest request) {
    try {
      HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
      return new Response(response.statusCode(), response.body());
    } catch (IOException e) {
      throw new IllegalStateException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
