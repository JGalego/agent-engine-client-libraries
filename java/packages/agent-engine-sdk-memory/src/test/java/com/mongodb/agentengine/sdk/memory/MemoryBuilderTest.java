package com.mongodb.agentengine.sdk.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mongodb.agentengine.sdk.api.v1.memory.SearchSemanticRequest;
import com.mongodb.agentengine.sdk.api.v1.memory.WriteTurnRequest;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MemoryBuilderTest {

  private static final String ACK = "{\"id\":\"t\",\"session_id\":\"s\",\"turn_seq\":1,\"acknowledged\":true}";

  private FakeServer server;
  private final Map<String, String> env = new HashMap<>();

  @BeforeEach
  void setUp() {
    server = new FakeServer();
  }

  @AfterEach
  void tearDown() {
    server.close();
  }

  private Memory.Builder builder() {
    return Memory.builder().environment(env::get);
  }

  @Test
  void aProjectSelectsScopedGatewayRoutesAndBearerAuth() {
    server.reply(200, ACK);

    try (Memory memory = builder().baseUrl(server.url()).serviceAccountToken("tok").projectId(" my proj ").build()) {
      memory.recordTurn(WriteTurnRequest.builder("user").build());
    }

    var request = server.only();
    assertEquals("/api/v1/projects/my%20proj/memory/turns", request.path());
    assertEquals("Bearer tok", request.header("Authorization"));
  }

  @Test
  void withoutAProjectItUsesFlatRoutesAndNoAuthHeader() {
    server.reply(200, ACK);

    builder().baseUrl(server.url()).build().recordTurn(WriteTurnRequest.builder("user").build());

    assertEquals("/api/v1/memory/turns", server.only().path());
    assertEquals(null, server.only().header("Authorization"));
  }

  @Test
  void configurationFallsBackToEnvironmentVariables() {
    server.reply(200, ACK);
    env.put("AGENTIC_MEMORY_BASE_URL", server.url());
    env.put("AGENTIC_MEMORY_SERVICE_ACCOUNT_TOKEN", "env-token");
    env.put("AGENTIC_MEMORY_PROJECT_ID", "env-project");

    builder().build().recordTurn(WriteTurnRequest.builder("user").build());

    assertEquals("/api/v1/projects/env-project/memory/turns", server.only().path());
    assertEquals("Bearer env-token", server.only().header("Authorization"));
  }

  @Test
  void argumentsBeatEnvironmentVariables() {
    server.reply(200, ACK);
    env.put("AGENTIC_MEMORY_BASE_URL", "http://unused.invalid");
    env.put("AGENTIC_MEMORY_PROJECT_ID", "env-project");

    builder().baseUrl(server.url()).projectId("arg-project").build().recordTurn(WriteTurnRequest.builder("user").build());

    assertTrue(server.only().path().contains("arg-project"));
  }

  @Test
  void blankEnvironmentValuesCountAsUnset() {
    env.put("AGENTIC_MEMORY_SERVICE_ACCOUNT_TOKEN", "  ");
    env.put("AGENTIC_MEMORY_PROJECT_ID", " ");
    server.reply(200, ACK);

    builder().baseUrl(server.url()).build().recordTurn(WriteTurnRequest.builder("user").build());

    assertEquals("/api/v1/memory/turns", server.only().path());
    assertEquals(null, server.only().header("Authorization"));
  }

  @Test
  void aTokenAloneTargetsTheHostedGateway() {
    // Building must not touch the network; the default URL is only used when a request is sent.
    builder().serviceAccountToken("tok").build().close();
  }

  @Test
  void aBackendIsRequired() {
    var error = assertThrows(IllegalArgumentException.class, () -> builder().build());

    assertTrue(error.getMessage().contains("AGENTIC_MEMORY_BASE_URL"));
  }

  @Test
  void blankArgumentsAreRejected() {
    assertThrows(IllegalArgumentException.class, () -> builder().baseUrl(" ").build());
    assertThrows(IllegalArgumentException.class, () -> builder().baseUrl("http://x").serviceAccountToken(" ").build());
  }

  @Test
  void searchesUseTheAliasedRoute() {
    server.reply(200, "{\"memories\":[]}");

    builder().baseUrl(server.url()).build().searchSemantic(SearchSemanticRequest.builder("q").userId("u").build());

    assertEquals("/api/v1/memory/search", server.only().path());
    assertEquals("semantic", server.only().json().get("type").asText());
  }
}
