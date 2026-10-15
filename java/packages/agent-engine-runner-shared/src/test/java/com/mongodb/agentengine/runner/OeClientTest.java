package com.mongodb.agentengine.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OeClientTest {

  private FakeServer oe;
  private OeClient client;

  @BeforeEach
  void setUp() {
    oe = new FakeServer();
    client = new OeClient(oe.url() + "/", Duration.ofSeconds(5));
  }

  @AfterEach
  void tearDown() {
    oe.close();
  }

  @Test
  void postsJsonAndReturnsTheResponse() throws Exception {
    oe.reply(200, "{\"ok\":true}");

    var response = client.postJson("/executor/callback", java.util.Map.of("a", 1), 1);

    assertTrue(response.ok());
    assertTrue(response.json().get("ok").asBoolean());
    assertEquals("application/json", oe.only().header("Content-Type"));
    assertEquals(1, oe.only().json().get("a").asInt());
  }

  @Test
  void retriesTransientFailuresUpToTheAttemptLimit() {
    oe.reply(503, "x").reply(502, "y").reply(200, "{}");

    assertTrue(client.postJson("/p", java.util.Map.of(), 3).ok());
    assertEquals(3, oe.requests().size());
  }

  @Test
  void doesNotRetryAClientError() {
    oe.reply(400, "bad").reply(200, "{}");

    assertEquals(400, client.postJson("/p", java.util.Map.of(), 3).status());
    assertEquals(1, oe.requests().size());
  }

  @Test
  void givesUpWithTheLastResponseWhenRetriesRunOut() {
    oe.reply(503, "one").reply(503, "two");

    assertEquals("two", client.postJson("/p", java.util.Map.of(), 2).body());
  }

  @Test
  void anUnreachableOeIsAnIoError() {
    oe.close();

    assertThrows(UncheckedIOException.class, () -> client.postJson("/p", java.util.Map.of(), 1));
  }

  @Test
  void readsServerSentEventsAndJoinsMultilineData() {
    oe.reply(200, "data: one\n\ndata: two\ndata: lines\n\n: comment\n\ndata: tail");
    List<String> received = new ArrayList<>();

    int status = client.postEvents(oe.url() + "/stream", Duration.ofSeconds(5), data -> received.add(data));

    assertEquals(200, status);
    assertEquals(List.of("one", "two\nlines", "tail"), received);
  }

  @Test
  void theHandlerCanStopTheStream() {
    oe.reply(200, "data: a\n\ndata: b\n\ndata: c\n\n");
    List<String> received = new ArrayList<>();

    client.postEvents(oe.url() + "/stream", Duration.ofSeconds(5), data -> {
      received.add(data);
      return !data.equals("b");
    });

    assertEquals(List.of("a", "b"), received);
  }

  @Test
  void aRelativeStreamPathResolvesAgainstTheOe() {
    oe.reply(200, "data: x\n\n");

    client.postEvents("/tool/stream/e/1", Duration.ofSeconds(5), data -> true);

    assertEquals("/tool/stream/e/1", oe.only().path());
    assertEquals("text/event-stream", oe.only().header("Accept"));
    assertEquals("POST", oe.only().method());
  }

  @Test
  void aNon2xxStreamNeverReachesTheHandler() {
    oe.reply(500, "data: nope\n\n");
    List<String> received = new ArrayList<>();

    int status = client.postEvents(oe.url() + "/stream", Duration.ofSeconds(5), received::add);

    assertEquals(500, status);
    assertTrue(received.isEmpty());
  }

  @Test
  void streamUrlsOutsideTheOeAreRefusedBeforeAnyRequest() {
    for (String url : new String[] {"http://attacker.invalid/x", oe.url() + ".evil.invalid/x", "http://127.0.0.1:1/x"}) {
      assertThrows(IllegalArgumentException.class, () -> client.postEvents(url, Duration.ofSeconds(1), data -> true), url);
    }
    assertFalse(oe.requests().iterator().hasNext());
  }
}
