package com.mongodb.agentengine.sdk.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.agentengine.sdk.Json;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/** A local HTTP server that records requests and replays queued responses. */
public final class FakeServer implements AutoCloseable {

  /** A recorded request. */
  public record Request(String method, String target, Map<String, List<String>> headers, String body) {

    public String path() {
      int q = target.indexOf('?');
      return q < 0 ? target : target.substring(0, q);
    }

    public String query() {
      int q = target.indexOf('?');
      return q < 0 ? "" : target.substring(q + 1);
    }

    public String header(String name) {
      for (var entry : headers.entrySet()) {
        if (entry.getKey().equalsIgnoreCase(name)) {
          return entry.getValue().get(0);
        }
      }
      return null;
    }

    public JsonNode json() {
      try {
        return Json.mapper().readTree(body);
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
    }
  }

  private record Reply(int status, String body) {}

  private final HttpServer server;
  private final Queue<Request> requests = new ConcurrentLinkedQueue<>();
  private final Queue<Reply> replies = new ArrayDeque<>();

  public FakeServer() {
    try {
      server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    server.createContext(
        "/",
        exchange -> {
          String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          requests.add(
              new Request(
                  exchange.getRequestMethod(),
                  exchange.getRequestURI().getRawPath()
                      + (exchange.getRequestURI().getRawQuery() == null
                          ? ""
                          : "?" + exchange.getRequestURI().getRawQuery()),
                  Map.copyOf(exchange.getRequestHeaders()),
                  body));
          Reply reply;
          synchronized (replies) {
            reply = replies.isEmpty() ? new Reply(500, "no reply queued") : replies.remove();
          }
          byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
          if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
          }
          exchange.close();
        });
    server.start();
  }

  public String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  /** Queues the next response. */
  public FakeServer reply(int status, String body) {
    synchronized (replies) {
      replies.add(new Reply(status, body));
    }
    return this;
  }

  public List<Request> requests() {
    return new ArrayList<>(requests);
  }

  /** The only recorded request. */
  public Request only() {
    List<Request> all = requests();
    if (all.size() != 1) {
      throw new AssertionError("expected one request, got " + all.size());
    }
    return all.get(0);
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
