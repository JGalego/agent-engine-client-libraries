package com.example.nearmiss.embed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.example.nearmiss.NearMissConfig;
import com.example.nearmiss.Vectors;
import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.agentengine.sdk.Json;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VoyageTextEmbedderTest {

  private HttpServer server;
  private final List<JsonNode> bodies = new ArrayList<>();
  private final List<String> failures = new ArrayList<>();

  /** A strict fake: anything the real endpoint would reject is a 400 here too. */
  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/embeddings", exchange -> {
      JsonNode body = Json.mapper().readTree(exchange.getRequestBody());
      bodies.add(body);
      if (!"POST".equals(exchange.getRequestMethod())) {
        failures.add("method " + exchange.getRequestMethod());
      }
      if (!"Bearer test-key".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
        failures.add("auth " + exchange.getRequestHeaders().getFirst("Authorization"));
      }
      if (!"application/json".equals(exchange.getRequestHeaders().getFirst("Content-Type"))) {
        failures.add("content type");
      }
      if (!body.path("input").isArray() || !body.hasNonNull("model")) {
        failures.add("input and model are required");
      }
      byte[] out;
      int status;
      if (failures.isEmpty()) {
        // Answer in reverse order: the client has to sort by index, not by position.
        var data = Json.mapper().createArrayNode();
        for (int i = body.path("input").size() - 1; i >= 0; i--) {
          data.addObject().put("object", "embedding").put("index", i)
              .set("embedding", Json.mapper().valueToTree(List.of(3.0 * (i + 1), 4.0 * (i + 1))));
        }
        var response = Json.mapper().createObjectNode().put("object", "list").put("model", body.path("model").asText());
        response.set("data", data);
        response.putObject("usage").put("total_tokens", 7);
        out = response.toString().getBytes(StandardCharsets.UTF_8);
        status = 200;
      } else {
        out = ("{\"detail\":\"" + String.join("; ", failures) + "\"}").getBytes(StandardCharsets.UTF_8);
        status = 400;
      }
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, out.length);
      exchange.getResponseBody().write(out);
      exchange.close();
    });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private VoyageTextEmbedder embedder() {
    return new VoyageTextEmbedder("test-key", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/", "voyage-4");
  }

  @Test
  void recordsAreDocumentsAndSearchesAreQueries() {
    VoyageTextEmbedder voyage = embedder();
    voyage.embed("insulin pump delivered too much insulin");
    voyage.embedQuery("insulin overdose");

    assertEquals(List.of(), failures);
    assertEquals("document", bodies.get(0).path("input_type").asText());
    assertEquals("query", bodies.get(1).path("input_type").asText());
    assertEquals("voyage-4", bodies.get(1).path("model").asText());
  }

  @Test
  void vectorsComeBackInInputOrderAndNormalized() {
    List<List<Double>> vectors = embedder().embedAll(List.of("first", "second"));

    assertEquals(List.of(), failures);
    assertEquals(List.of(0.6, 0.8), vectors.get(0));
    assertEquals(List.of(0.6, 0.8), vectors.get(1));
    assertEquals(2, bodies.get(0).path("input").size());
  }

  @Test
  void theModelIdNamesVoyageSoItsVectorsAreNeverMixedWithMiniLm() {
    VoyageTextEmbedder voyage = embedder();
    assertEquals("voyage/voyage-4", voyage.modelId());
    assertEquals(2, voyage.dimensions());
  }

  @Test
  void anHttpErrorIsReportedNotSwallowed() {
    VoyageTextEmbedder wrongKey =
        new VoyageTextEmbedder("other-key", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "voyage-4");
    IllegalStateException e = assertThrows(IllegalStateException.class, () -> wrongKey.embed("x"));
    assertTrue(e.getMessage().contains("HTTP 400"), e.getMessage());
  }

  @Test
  void aMissingKeyFailsAtStartup() {
    assertThrows(IllegalStateException.class, () -> new VoyageTextEmbedder(null, "https://ai.mongodb.com/v1", "voyage-4"));
  }

  /** Runs against the real endpoint only when VOYAGE_API_KEY is set. */
  @Test
  void liveQueryAndDocumentVectorsAgree() {
    NearMissConfig config = NearMissConfig.load();
    assumeTrue(config.voyageKey() != null, "VOYAGE_API_KEY is not set");
    VoyageTextEmbedder voyage = new VoyageTextEmbedder(config);

    List<Double> report = voyage.embed("Pilot reported engine loss of power inflight during the takeoff climb.");
    List<Double> query = voyage.embedQuery("engine lost power during climb");
    List<Double> unrelated = voyage.embedQuery("insulin pump delivered too much insulin");

    assertTrue(Vectors.cosine(report, query) > Vectors.cosine(report, unrelated));
  }
}
