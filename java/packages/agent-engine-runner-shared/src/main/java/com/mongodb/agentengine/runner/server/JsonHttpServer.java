package com.mongodb.agentengine.runner.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.mongodb.agentengine.sdk.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A small JSON-over-HTTP server on the JDK's built-in {@link HttpServer}.
 *
 * <p>Handlers return the response body, which is serialized as JSON, and report failures by
 * throwing {@link HttpError}. A route path may name segments in braces, such as {@code
 * /sessions/{id}/messages}; their values are in {@link Request#pathParams()}. Unknown paths answer 404 and known paths with another method answer
 * 405, both as {@code {"detail": ...}}.
 */
public final class JsonHttpServer implements AutoCloseable {

  /** A request as a handler sees it. */
  public record Request(String method, URI uri, byte[] body, Map<String, String> pathParams) {

    /** The raw query string, or the empty string. */
    public String query() {
      return uri.getRawQuery() == null ? "" : uri.getRawQuery();
    }

    /** The decoded values of a query parameter, in order; repeated parameters give several. */
    public List<String> queryValues(String name) {
      List<String> values = new ArrayList<>();
      for (String pair : query().split("&")) {
        int eq = pair.indexOf('=');
        String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
        if (key.equals(name)) {
          values.add(eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
      }
      return values;
    }

    /**
     * Parses the body as JSON into {@code type}.
     *
     * @throws HttpError 422 if the body is not valid for the type
     */
    public <T> T json(Class<T> type) {
      try {
        return Json.mapper().readValue(body, type);
      } catch (IOException e) {
        throw new HttpError(422, "Invalid request body: " + firstLine(e.getMessage()));
      }
    }
  }

  /** Writes server-sent events to the client as they are produced. */
  public interface EventSink {

    /** Sends one event whose data is {@code payload} serialized as JSON. */
    void send(Object payload) throws IOException;
  }

  /** The body of a streamed response. */
  @FunctionalInterface
  public interface EventStream {
    void write(EventSink sink) throws Exception;
  }

  /** The result of a handler. */
  public record Reply(int status, Object body) {

    /** A {@code text/event-stream} response produced by {@code stream}. */
    public static Reply events(EventStream stream) {
      return new Reply(200, stream);
    }


    public static Reply ok(Object body) {
      return new Reply(200, body);
    }

    public static Reply noContent() {
      return new Reply(204, null);
    }
  }

  /** A route handler. */
  @FunctionalInterface
  public interface Handler {
    Reply handle(Request request) throws Exception;
  }

  private static final System.Logger LOG = System.getLogger(JsonHttpServer.class.getName());

  private final HttpServer server;
  private final ExecutorService executor;
  private final Map<String, Map<String, Handler>> routes = new LinkedHashMap<>();

  /** Creates a server bound to {@code host:port}; port 0 picks a free port. */
  public JsonHttpServer(String host, int port) throws IOException {
    this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
    AtomicInteger threads = new AtomicInteger();
    this.executor =
        Executors.newCachedThreadPool(
            task -> {
              Thread thread = new Thread(task, "runner-http-" + threads.incrementAndGet());
              thread.setDaemon(true);
              return thread;
            });
    server.setExecutor(executor);
    server.createContext("/", this::dispatch);
  }

  /** Registers a handler for {@code method} on {@code path}. */
  public JsonHttpServer route(String method, String path, Handler handler) {
    routes.computeIfAbsent(path, p -> new LinkedHashMap<>()).put(method, handler);
    return this;
  }

  public void start() {
    server.start();
  }

  /** The port the server is bound to. */
  public int port() {
    return server.getAddress().getPort();
  }

  @Override
  public void close() {
    server.stop(0);
    executor.shutdownNow();
  }

  private void dispatch(HttpExchange exchange) throws IOException {
    int status;
    Object body;
    try {
      String path = exchange.getRequestURI().getRawPath();
      Map<String, String> pathParams = new LinkedHashMap<>();
      Map<String, Handler> byMethod = routes.get(path);
      if (byMethod == null) {
        byMethod = matchTemplate(path, pathParams);
      }
      if (byMethod == null) {
        throw new HttpError(404, "Not Found");
      }
      Handler handler = byMethod.get(exchange.getRequestMethod());
      if (handler == null) {
        throw new HttpError(405, "Method Not Allowed");
      }
      byte[] requestBody = exchange.getRequestBody().readAllBytes();
      Reply reply = handler.handle(new Request(exchange.getRequestMethod(), exchange.getRequestURI(), requestBody, pathParams));
      status = reply.status();
      body = reply.body();
    } catch (HttpError e) {
      status = e.status();
      body = Map.of("detail", e.getMessage() == null ? "" : e.getMessage());
    } catch (Exception e) {
      LOG.log(System.Logger.Level.ERROR, "Unhandled error serving " + exchange.getRequestURI().getPath(), e);
      status = 500;
      body = Map.of("detail", "Internal Server Error");
    }
    if (body instanceof EventStream stream) {
      writeEvents(exchange, stream);
    } else {
      write(exchange, status, body);
    }
  }

  /** Finds the route whose braced segments match {@code path}, collecting their decoded values. */
  private Map<String, Handler> matchTemplate(String path, Map<String, String> pathParams) {
    String[] actual = path.split("/", -1);
    for (Map.Entry<String, Map<String, Handler>> route : routes.entrySet()) {
      String[] template = route.getKey().split("/", -1);
      if (template.length != actual.length) {
        continue;
      }
      Map<String, String> found = new LinkedHashMap<>();
      boolean matches = true;
      for (int i = 0; i < template.length && matches; i++) {
        if (template[i].startsWith("{") && template[i].endsWith("}")) {
          matches = !actual[i].isEmpty();
          found.put(template[i].substring(1, template[i].length() - 1), URLDecoder.decode(actual[i].replace("+", "%2B"), StandardCharsets.UTF_8));
        } else {
          matches = template[i].equals(actual[i]);
        }
      }
      if (matches) {
        pathParams.putAll(found);
        return route.getValue();
      }
    }
    return null;
  }

  private static void writeEvents(HttpExchange exchange, EventStream stream) throws IOException {
    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
    exchange.getResponseHeaders().set("Cache-Control", "no-cache");
    exchange.sendResponseHeaders(200, 0);
    try (OutputStream out = exchange.getResponseBody()) {
      EventSink sink =
          payload -> {
            out.write(("data: " + Json.mapper().writeValueAsString(payload) + "\n\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
          };
      try {
        stream.write(sink);
      } catch (IOException e) {
        throw e; // the client went away; there is nobody to tell
      } catch (Exception e) {
        LOG.log(System.Logger.Level.ERROR, "Event stream failed", e);
      }
    }
    exchange.close();
  }

  private static void write(HttpExchange exchange, int status, Object body) throws IOException {
    byte[] bytes = new byte[0];
    if (body != null) {
      try {
        bytes = Json.mapper().writeValueAsBytes(body);
      } catch (JsonProcessingException e) {
        status = 500;
        bytes = "{\"detail\":\"Response is not serializable\"}".getBytes(StandardCharsets.UTF_8);
      }
      exchange.getResponseHeaders().set("Content-Type", "application/json");
    }
    boolean empty = bytes.length == 0;
    exchange.sendResponseHeaders(status, empty ? -1 : bytes.length);
    if (!empty) {
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(bytes);
      }
    }
    exchange.close();
  }

  private static String firstLine(String message) {
    if (message == null) {
      return "";
    }
    int newline = message.indexOf('\n');
    return newline < 0 ? message : message.substring(0, newline);
  }
}
