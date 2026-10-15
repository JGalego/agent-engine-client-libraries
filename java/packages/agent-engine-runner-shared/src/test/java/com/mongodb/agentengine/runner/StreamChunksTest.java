package com.mongodb.agentengine.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StreamChunksTest {

  private FakeServer oe;

  @BeforeEach
  void setUp() {
    oe = new FakeServer();
  }

  @AfterEach
  void tearDown() {
    oe.close();
  }

  private StreamChunks chunks() {
    return new StreamChunks(new OeClient(oe.url(), Duration.ofSeconds(5)), "exec-1");
  }

  @Test
  void textIsPostedInOrderWithRisingSequenceNumbers() {
    oe.reply(200, "{}").reply(200, "{}");

    try (StreamChunks chunks = chunks()) {
      chunks.text("Hel");
      chunks.text("lo");
    }

    List<FakeServer.Request> sent = oe.requests();
    assertEquals(2, sent.size());
    JsonNode first = sent.get(0).json();
    assertEquals("/stream/chunk", sent.get(0).path());
    assertEquals("exec-1", first.get("execution_id").asText());
    assertEquals("text", first.get("chunk_type").asText());
    assertEquals("Hel", first.get("content").asText());
    assertEquals(1, first.get("seq").asInt());
    assertEquals("lo", sent.get(1).json().get("content").asText());
    assertEquals(2, sent.get(1).json().get("seq").asInt());
  }

  @Test
  void emptyTextIsNotSent() {
    try (StreamChunks chunks = chunks()) {
      chunks.text("");
      chunks.text(null);
    }

    assertTrue(oe.requests().isEmpty());
  }

  @Test
  void aChunkTheOeRejectsIsDroppedWithoutFailingTheRest() {
    oe.reply(400, "bad").reply(200, "{}");

    try (StreamChunks chunks = chunks()) {
      chunks.text("a");
      chunks.text("b");
    }

    assertEquals(2, oe.requests().size());
  }
}
