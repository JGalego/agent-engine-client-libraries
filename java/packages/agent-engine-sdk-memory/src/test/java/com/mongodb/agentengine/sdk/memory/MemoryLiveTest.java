package com.mongodb.agentengine.sdk.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.agentengine.sdk.Json;
import com.mongodb.agentengine.sdk.api.v1.memory.BuildContextRequest;
import com.mongodb.agentengine.sdk.api.v1.memory.CreateEpisodicRequest;
import com.mongodb.agentengine.sdk.api.v1.memory.CreateSemanticRequest;
import com.mongodb.agentengine.sdk.api.v1.memory.GetSemanticRequest;
import com.mongodb.agentengine.sdk.api.v1.memory.ListEpisodicRequest;
import com.mongodb.agentengine.sdk.api.v1.memory.MemorySource;
import com.mongodb.agentengine.sdk.api.v1.memory.SearchSemanticRequest;
import com.mongodb.agentengine.sdk.api.v1.memory.WriteTurnRequest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Exercises the memory facade against a real backend. It writes a few records under a unique user
 * id and does not clean them up. It is skipped unless one of these is configured, and while the
 * backend has no memory runtime:
 *
 * <ul>
 *   <li>a local stack from {@code agentengine dev up}: set {@code AGENTIC_MEMORY_BASE_URL} to its
 *       OE URL;
 *   <li>the hosted platform: set {@code AGENTENGINE_CLIENT_ID}, {@code AGENTENGINE_CLIENT_SECRET}
 *       and {@code AGENTENGINE_PROJECT_ID}, and optionally {@code AGENTENGINE_BASE_URL}.
 * </ul>
 */
@EnabledIf("configured")
class MemoryLiveTest {

  private static String baseUrl;
  private static String projectId;
  private static String token;

  static boolean configured() {
    return isSet("AGENTIC_MEMORY_BASE_URL") || isSet("AGENTENGINE_CLIENT_ID");
  }

  private static boolean isSet(String name) {
    String value = System.getenv(name);
    return value != null && !value.isBlank();
  }

  private final String user = "java-sdk-it-" + UUID.randomUUID();

  @BeforeAll
  static void authenticate() throws Exception {
    if (isSet("AGENTIC_MEMORY_BASE_URL")) {
      return; // local stack: no token, flat routes
    }
    baseUrl = System.getenv().getOrDefault("AGENTENGINE_BASE_URL", "https://agentengine.mongodb.com");
    projectId = System.getenv("AGENTENGINE_PROJECT_ID");
    String credentials = System.getenv("AGENTENGINE_CLIENT_ID") + ":" + System.getenv("AGENTENGINE_CLIENT_SECRET");
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/oauth/token"))
            .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials"))
            .build();
    HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode(), "token exchange failed");
    token = Json.mapper().readTree(response.body()).get("access_token").asText();
  }

  /** A project gets a memory runtime only once an agent with {@code features.memory} is deployed. */
  @BeforeEach
  void requireProvisionedMemory() {
    try (Memory memory = memory()) {
      memory.listDomains();
    } catch (MemoryNotProvisionedException e) {
      Assumptions.abort("project has no memory runtime yet: " + e.getMessage());
    }
  }

  /**
   * Retries a call the embedding service throttled. A key with a low requests-per-minute limit
   * (such as a free Voyage tier) rejects bursts, and every embedding-backed call can hit it.
   */
  private static <T> T unthrottled(Supplier<T> call) {
    for (int attempt = 1; ; attempt++) {
      try {
        return call.get();
      } catch (MemoryBadRequestException e) {
        if (attempt >= 4 || e.getMessage() == null || !e.getMessage().contains("rate limit")) {
          throw e;
        }
        try {
          Thread.sleep(21_000);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw e;
        }
      }
    }
  }

  private Memory memory() {
    if (token == null) {
      return Memory.builder().build();
    }
    return Memory.builder().baseUrl(baseUrl).serviceAccountToken(token).projectId(projectId).build();
  }

  @Test
  void semanticRoundTrip() throws Exception {
    try (Memory memory = memory()) {
      String label = "favorite_color_" + UUID.randomUUID().toString().substring(0, 8);

      var saved = unthrottled(() -> memory.saveSemantic(CreateSemanticRequest.builder(label, "the user's favorite color is teal").userId(user).build()));
      System.out.println("saveSemantic -> " + saved);
      JsonNode found = memory.getSemantic(GetSemanticRequest.builder().label(label).userId(user).build()).orElse(null);
      System.out.println("getSemantic -> " + found);
      var hits = unthrottled(() -> memory.searchSemantic(SearchSemanticRequest.builder("what color does the user like").userId(user).build()));
      System.out.println("searchSemantic -> " + hits);

      assertEquals(label, saved.label());
      assertTrue(saved.acknowledged());
    }
  }

  @Test
  void conversationLoop() throws Exception {
    try (Memory memory = memory().bind(MemoryRequestContext.of(user, "session-" + UUID.randomUUID()))) {
      var turn = unthrottled(() -> memory.recordTurn(WriteTurnRequest.builder("user").content("I am allergic to penicillin.").build()));
      System.out.println("recordTurn -> " + turn);
      var context =
          unthrottled(
              () ->
                  memory.buildContext(
                      BuildContextRequest.builder("What medications should I avoid?").enabledSources(Set.of(MemorySource.STM, MemorySource.SEMANTIC)).build()));
      System.out.println("buildContext -> " + context);

      assertFalse(turn.id().isBlank());
      assertTrue(context.formattedContext().isTextual() || context.formattedContext().isArray());
    }
  }

  @Test
  void episodes() throws Exception {
    try (Memory memory = memory().bind(MemoryRequestContext.of(user, "session-" + UUID.randomUUID()))) {
      var saved = unthrottled(() -> memory.saveEpisode(CreateEpisodicRequest.builder("Onboarding chat", "User asked about billing.").summaryText("Billing question").build()));
      System.out.println("saveEpisode -> " + saved);
      System.out.println("listEpisodes -> " + memory.listEpisodes(ListEpisodicRequest.builder().build()));

      assertEquals("Onboarding chat", saved.title());
    }
  }
}
