package com.mongodb.agentengine.sdk.clients;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.agentengine.sdk.BranchRef;
import com.mongodb.agentengine.sdk.Event;
import com.mongodb.agentengine.sdk.Json;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EventClientTest {

  private static final String EVENT =
      "{\"event_id\":\"e1\",\"session_id\":\"s 1\",\"actor_id\":\"a\",\"payload\":{\"k\":1},"
          + "\"timestamp\":\"2026-01-02T03:04:05Z\"}";

  private FakeServer server;
  private EventClient client;

  @BeforeEach
  void setUp() {
    server = new FakeServer();
    client = new EventClient(server.url() + "/");
  }

  @AfterEach
  void tearDown() {
    server.close();
  }

  private static JsonNode json(String text) throws Exception {
    return Json.mapper().readTree(text);
  }

  @Test
  void appendEventPostsToTheEncodedSessionPath() throws Exception {
    server.reply(200, "{\"event\":" + EVENT + "}");

    Event event = client.appendEvent("s 1", "a", json("{\"k\":1}"));

    var request = server.only();
    assertEquals("POST", request.method());
    assertEquals("/v1/sessions/s%201/events", request.path());
    assertEquals("application/json", request.header("Content-Type"));
    assertEquals(json("{\"actor_id\":\"a\",\"payload\":{\"k\":1}}"), request.json());
    assertEquals("e1", event.eventId());
    assertEquals(Instant.parse("2026-01-02T03:04:05Z"), event.timestamp());
  }

  @Test
  void appendEventSendsOptionalFieldsWhenGiven() throws Exception {
    server.reply(200, "{\"event\":" + EVENT + "}");

    client.appendEvent("s", "a", json("1"), "p1", Map.of("k", "v"), new BranchRef("b", "r"));

    assertEquals(
        json("{\"actor_id\":\"a\",\"payload\":1,\"parent_event_id\":\"p1\",\"metadata\":{\"k\":\"v\"},"
            + "\"branch\":{\"name\":\"b\",\"root_event_id\":\"r\"}}"),
        server.only().json());
  }

  @Test
  void listEventsPassesBranchAndMetadataFilters() {
    server.reply(200, "{\"events\":[" + EVENT + "]}");
    Map<String, String> filters = new LinkedHashMap<>();
    filters.put("kind", "tool call");

    List<Event> events = client.listEvents("s", "main", filters);

    assertEquals("branch=main&metadata.kind=tool+call", server.only().query());
    assertEquals(1, events.size());
  }

  @Test
  void listEventsWithoutFiltersHasNoQuery() {
    server.reply(200, "{\"events\":[]}");

    assertTrue(client.listEvents("s").isEmpty());
    assertEquals("", server.only().query());
  }

  @Test
  void getEventReturnsEmptyOn404() {
    server.reply(404, "{}");

    assertTrue(client.getEvent("s", "missing").isEmpty());
  }

  @Test
  void getEventParsesTheEvent() {
    server.reply(200, "{\"event\":" + EVENT + "}");

    assertEquals("e1", client.getEvent("s", "e/1").orElseThrow().eventId());
    assertEquals("/v1/sessions/s/events/e%2F1", server.only().path());
  }

  @Test
  void errorStatusThrowsWithBody() {
    server.reply(500, "boom");

    HttpStatusException error = assertThrows(HttpStatusException.class, () -> client.listEvents("s"));

    assertEquals(500, error.status());
    assertEquals("boom", error.responseText());
    assertEquals("HTTP 500: boom", error.getMessage());
  }

  @Test
  void bareDotSegmentsAreRefused() {
    assertThrows(IllegalArgumentException.class, () -> client.listEvents(".."));
    assertThrows(IllegalArgumentException.class, () -> client.getEvent("s", "."));
    assertTrue(server.requests().isEmpty());
  }

  @Test
  void malformedBodyIsAResponseFormatError() {
    server.reply(200, "{\"events\":[{\"event_id\":\"e\"}]}");

    assertThrows(ResponseFormatException.class, () -> client.listEvents("s"));
  }

  @Test
  void unreachableServerThrowsUncheckedIoException() {
    server.close();

    assertThrows(UncheckedIOException.class, () -> client.listEvents("s"));
  }
}
