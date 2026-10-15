package com.mongodb.agentengine.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.agentengine.sdk.NodeOptions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NodeExecutionLoggerTest {

  private FakeServer oe;

  @BeforeEach
  void setUp() {
    oe = new FakeServer();
    oe.reply(200, "{}").reply(200, "{}").reply(200, "{}");
  }

  @AfterEach
  void tearDown() {
    oe.close();
  }

  private NodeExecutionLogger logger() {
    return new NodeExecutionLogger(new OeClient(oe.url(), Duration.ofSeconds(5)), "exec-1", "s1", "ana", "org-1", "proj-1");
  }

  private static NodeOptions run(String id) {
    return new NodeOptions(id, null, null, Map.of());
  }

  @Test
  void aSuccessfulNodeIsReportedAsStartedThenSuccess() {
    try (NodeExecutionLogger logger = logger()) {
      logger.onNodeStart("agent", Map.of("messages", List.of("hi")), run("r1"));
      logger.onNodeEnd("agent", Map.of("answer", 42), run("r1"));
    }

    List<FakeServer.Request> sent = oe.requests();
    assertEquals(2, sent.size());
    JsonNode started = sent.get(0).json();
    assertEquals("/node/execution", sent.get(0).path());
    assertEquals("exec-1", started.get("execution_id").asText());
    assertEquals("s1", started.get("session_id").asText());
    assertEquals("org-1", started.get("org_id").asText());
    assertEquals("agent", started.get("node_name").asText());
    assertEquals("started", started.get("status").asText());
    assertEquals("r1", started.get("run_id").asText());
    assertEquals("hi", started.get("inputs").get("messages").get(0).asText());
    JsonNode ended = sent.get(1).json();
    assertEquals("success", ended.get("status").asText());
    assertEquals(42, ended.get("outputs").get("answer").asInt());
    assertTrue(ended.get("duration_ms").asDouble() >= 0);
  }

  @Test
  void aFailedNodeCarriesItsError() {
    try (NodeExecutionLogger logger = logger()) {
      logger.onNodeError("tools", "boom", run("r2"));
    }

    JsonNode event = oe.only().json();
    assertEquals("error", event.get("status").asText());
    assertEquals("boom", event.get("error").asText());
  }

  @Test
  void valuesThatAreNotPlainJsonAreReportedAsText() {
    Object safe = NodeExecutionLogger.jsonSafe(Map.of("k", new StringBuilder("text")));

    assertEquals(Map.of("k", "text"), safe);
  }
}
