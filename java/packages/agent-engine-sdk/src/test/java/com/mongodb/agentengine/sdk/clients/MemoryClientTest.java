package com.mongodb.agentengine.sdk.clients;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.agentengine.sdk.Json;
import com.mongodb.agentengine.sdk.api.v1.memory.BuildContextRequest;
import com.mongodb.agentengine.sdk.api.v1.memory.ContextResponse;
import com.mongodb.agentengine.sdk.api.v1.memory.CreateCustomRequest;
import com.mongodb.agentengine.sdk.api.v1.memory.CreateEpisodicRequest;
import com.mongodb.agentengine.sdk.api.v1.memory.CreateProceduralRequest;
import com.mongodb.agentengine.sdk.api.v1.memory.CreateSemanticRequest;
import com.mongodb.agentengine.sdk.api.v1.memory.CreateTaxonomicRequest;
import com.mongodb.agentengine.sdk.api.v1.memory.DeleteProceduralRequest;
import com.mongodb.agentengine.sdk.api.v1.memory.FormatStyle;
import com.mongodb.agentengine.sdk.api.v1.memory.GetProceduralRequest;
import com.mongodb.agentengine.sdk.api.v1.memory.GetSemanticRequest;
import com.mongodb.agentengine.sdk.api.v1.memory.GetTaxonomicRequest;
import com.mongodb.agentengine.sdk.api.v1.memory.ListEpisodicRequest;
import com.mongodb.agentengine.sdk.api.v1.memory.MemoryChunk;
import com.mongodb.agentengine.sdk.api.v1.memory.MemorySource;
import com.mongodb.agentengine.sdk.api.v1.memory.RetrieveCustomRequest;
import com.mongodb.agentengine.sdk.api.v1.memory.SearchEpisodicRequest;
import com.mongodb.agentengine.sdk.api.v1.memory.SearchProceduralRequest;
import com.mongodb.agentengine.sdk.api.v1.memory.SearchSemanticRequest;
import com.mongodb.agentengine.sdk.api.v1.memory.SearchTaxonomicRequest;
import com.mongodb.agentengine.sdk.api.v1.memory.UpdateProceduralRequest;
import com.mongodb.agentengine.sdk.api.v1.memory.UpdateSemanticRequest;
import com.mongodb.agentengine.sdk.api.v1.memory.WriteTurnRequest;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MemoryClientTest {

  private static final String CHUNKS =
      "{\"memories\":[{\"id\":\"m1\",\"content\":\"teal\",\"source\":\"semantic\","
          + "\"timestamp\":\"2026-01-02T03:04:05Z\",\"similarity_score\":0.9,\"metadata\":{\"a\":1}}]}";

  private FakeServer server;

  @BeforeEach
  void setUp() {
    server = new FakeServer();
  }

  @AfterEach
  void tearDown() {
    server.close();
  }

  private MemoryClient.Builder client() {
    return MemoryClient.builder(server.url());
  }

  private static JsonNode json(String text) throws Exception {
    return Json.mapper().readTree(text);
  }

  // --- Short-term memory and context ------------------------------------------------------

  @Test
  void writeTurnPostsToTheNativeRoute() throws Exception {
    server.reply(200, "{\"id\":\"t1\",\"session_id\":\"s1\",\"turn_seq\":3,\"acknowledged\":true}");

    var result =
        client()
            .build()
            .writeTurn(
                WriteTurnRequest.builder("user").content("hi").sessionId(" s1 ").userId("u1").idempotencyKey("k").build());

    var request = server.only();
    assertEquals("/api/v1/memory/stm/turns", request.path());
    assertEquals(
        json("{\"session_id\":\"s1\",\"role\":\"user\",\"org_id\":\"\",\"user_id\":\"u1\",\"project_id\":\"\","
            + "\"content\":\"hi\",\"is_error\":false,\"idempotency_key\":\"k\"}"),
        request.json());
    assertEquals(3, result.turnSeq());
    assertTrue(result.acknowledged());
  }

  @Test
  void writeTurnSendsExplicitNullIdentityWhenAbsent() throws Exception {
    server.reply(200, "{\"id\":\"t1\",\"session_id\":\"s\",\"turn_seq\":1,\"acknowledged\":true}");

    client().build().writeTurn(WriteTurnRequest.builder("user").sessionId("  ").build());

    JsonNode body = server.only().json();
    assertTrue(body.has("session_id") && body.get("session_id").isNull());
    assertTrue(body.has("user_id") && body.get("user_id").isNull());
    assertFalse(body.has("content"));
  }

  @Test
  void aliasedRouteStyleUsesTurnsAndContext() {
    server
        .reply(200, "{\"id\":\"t1\",\"session_id\":\"s\",\"turn_seq\":1,\"acknowledged\":true}")
        .reply(200, "{\"formatted_context\":\"ctx\",\"metadata\":{}}");
    MemoryClient aliased = client().routeStyle(MemoryRouteStyle.ALIASED).apiPrefix("api/v1/projects/p/memory/").build();

    aliased.writeTurn(WriteTurnRequest.builder("user").build());
    aliased.buildContext(BuildContextRequest.builder("q").build());

    assertEquals(
        List.of("/api/v1/projects/p/memory/turns", "/api/v1/projects/p/memory/context"),
        server.requests().stream().map(FakeServer.Request::path).toList());
  }

  @Test
  void buildContextSendsSortedSourcesAndDefaults() throws Exception {
    server.reply(
        200,
        "{\"formatted_context\":[{\"role\":\"system\",\"content\":\"x\"}],\"metadata\":{\"token_count\":12,"
            + "\"memory_counts\":{\"semantic\":2},\"timing\":{\"total\":0.5}},\"selected_memories\":null}");

    ContextResponse context =
        client()
            .build()
            .buildContext(
                BuildContextRequest.builder("q")
                    .enabledSources(new LinkedHashSet<>(List.of(MemorySource.SEMANTIC, MemorySource.EPISODIC, MemorySource.STM)))
                    .formatStyle(FormatStyle.CLAUDE)
                    .metadataFilter(Map.of())
                    .maxTokens(2000)
                    .build());

    var request = server.only();
    assertEquals("/api/v1/memory/retrieval/context", request.path());
    JsonNode body = request.json();
    assertEquals(json("[\"episodic\",\"semantic\",\"stm\"]"), body.get("enabled_sources"));
    assertEquals("claude", body.get("format_style").asText());
    assertEquals(50, body.get("top_k").asInt());
    assertEquals(2000, body.get("max_tokens").asInt());
    assertFalse(body.has("metadata_filter"));
    assertTrue(context.formattedContext().isArray());
    assertEquals(12, context.metadata().tokenCount());
    assertEquals(2, context.metadata().memoryCounts().get("semantic"));
    assertNull(context.selectedMemories());
  }

  @Test
  void buildContextRequestRejectsNonPositiveBudget() {
    assertThrows(IllegalArgumentException.class, () -> BuildContextRequest.builder("q").maxTokens(0).build());
    assertThrows(IllegalArgumentException.class, () -> BuildContextRequest.builder("q").maxTokens(-5).build());
  }

  // --- Semantic memory ----------------------------------------------------------------------

  @Test
  void createSemanticAppliesDefaults() throws Exception {
    server.reply(200, "{\"id\":\"1\",\"label\":\"color\",\"has_embedding\":true,\"acknowledged\":true}");

    var result =
        client().build().createSemantic(CreateSemanticRequest.builder("color", "teal").userId("u1").build());

    assertEquals("/api/v1/memory/semantic", server.only().path());
    assertEquals(
        json("{\"label\":\"color\",\"text\":\"teal\",\"org_id\":\"\",\"user_id\":\"u1\",\"source\":\"agent\","
            + "\"visibility\":\"private\",\"project_id\":\"\",\"upsert\":false}"),
        server.only().json());
    assertTrue(result.hasEmbedding());
  }

  @Test
  void createSemanticRequiresUser() {
    assertThrows(
        IllegalArgumentException.class,
        () -> client().build().createSemantic(CreateSemanticRequest.builder("l", "t").build()));
    assertTrue(server.requests().isEmpty());
  }

  @Test
  void createSemanticKeepsNullsInsideMetadata() throws Exception {
    server.reply(200, "{\"id\":\"1\",\"label\":\"l\",\"has_embedding\":false,\"acknowledged\":true}");
    Map<String, Object> metadata = new java.util.HashMap<>();
    metadata.put("channel", "web");
    metadata.put("owner", null);

    client().build().createSemantic(CreateSemanticRequest.builder("l", "t").userId("u").metadata(metadata).build());

    assertEquals(json("{\"channel\":\"web\",\"owner\":null}"), server.only().json().get("metadata"));
  }

  @Test
  void searchRoutesDependOnStyle() throws Exception {
    server.reply(200, CHUNKS).reply(200, CHUNKS);

    List<MemoryChunk> nativeHits =
        client().build().fetchSemanticMemories(SearchSemanticRequest.builder("color").userId("u1").build());
    client()
        .routeStyle(MemoryRouteStyle.ALIASED)
        .build()
        .fetchSemanticMemories(SearchSemanticRequest.builder("color").build());

    var requests = server.requests();
    assertEquals("/api/v1/memory/retrieval/semantic", requests.get(0).path());
    assertFalse(requests.get(0).json().has("type"));
    assertEquals("/api/v1/memory/search", requests.get(1).path());
    assertEquals("semantic", requests.get(1).json().get("type").asText());
    assertEquals(50, requests.get(1).json().get("top_k").asInt());
    MemoryChunk chunk = nativeHits.get(0);
    assertEquals(MemorySource.SEMANTIC, chunk.source());
    assertEquals(Instant.parse("2026-01-02T03:04:05Z"), chunk.timestamp());
    assertEquals(0.9, chunk.similarityScore());
    assertEquals(Map.of("a", 1), chunk.metadata());
  }

  @Test
  void getSemanticByIdEncodesIdAndTreatsNotFoundAsEmpty() {
    server.reply(200, "{\"id\":\"a/b\",\"label\":\"x\"}").reply(404, "");
    MemoryClient client = client().build();
    GetSemanticRequest request = GetSemanticRequest.builder().id("a/b").userId("u").build();

    assertEquals("x", client.getSemantic(request).orElseThrow().get("label").asText());
    assertTrue(client.getSemantic(request).isEmpty());

    assertEquals("/api/v1/memory/semantic/a%2Fb", server.requests().get(0).path());
    assertEquals("org_id=&project_id=&user_id=u", server.requests().get(0).query());
  }

  @Test
  void getSemanticByLabelReturnsTheFirstEntry() {
    server.reply(200, "{\"entries\":[{\"id\":\"1\"},{\"id\":\"2\"}]}").reply(200, "{\"entries\":[]}");
    MemoryClient client = client().build();

    assertEquals("1", client.getSemantic(GetSemanticRequest.builder().label("l").build()).orElseThrow().get("id").asText());
    assertTrue(client.getSemantic(GetSemanticRequest.builder().label("l").build()).isEmpty());
    assertEquals("org_id=&project_id=&label=l", server.requests().get(0).query());
  }

  @Test
  void updateSemanticLooksUpTheIdFirst() throws Exception {
    server.reply(200, "{\"entries\":[{\"_id\":\"abc\"}]}").reply(200, "{\"id\":\"abc\",\"text\":\"new\"}");

    JsonNode updated = client().build().updateSemantic(UpdateSemanticRequest.builder("l").text("new").build());

    var patch = server.requests().get(1);
    assertEquals("PATCH", patch.method());
    assertEquals("/api/v1/memory/semantic/abc", patch.path());
    assertEquals(json("{\"org_id\":\"\",\"project_id\":\"\",\"text\":\"new\"}"), patch.json());
    assertEquals("new", updated.get("text").asText());
  }

  @Test
  void updateSemanticFailsWhenTheLabelIsUnknown() {
    server.reply(200, "{\"entries\":[]}");

    assertThrows(
        NoSuchElementException.class,
        () -> client().build().updateSemantic(UpdateSemanticRequest.builder("missing").build()));
  }

  // --- Episodic memory ------------------------------------------------------------------------

  @Test
  void createEpisodicRequiresASession() {
    CreateEpisodicRequest request = CreateEpisodicRequest.builder("t", "c").userId("u").sessionId(" ").build();

    assertThrows(IllegalArgumentException.class, () -> client().build().createEpisodic(request));
  }

  @Test
  void createEpisodicSendsOnlyGivenOptionalFields() throws Exception {
    server.reply(200, "{\"id\":\"1\",\"title\":\"t\",\"has_embedding\":false,\"acknowledged\":true}");

    client()
        .build()
        .createEpisodic(
            CreateEpisodicRequest.builder("t", "c").userId("u").sessionId(" s ").tags(List.of("x")).build());

    assertEquals(
        json("{\"title\":\"t\",\"content\":\"c\",\"summary_text\":\"\",\"org_id\":\"\",\"user_id\":\"u\","
            + "\"session_id\":\"s\",\"visibility\":\"private\",\"project_id\":\"\",\"tags\":[\"x\"]}"),
        server.only().json());
  }

  @Test
  void episodicSearchAndListCarryTheSessionFilter() throws Exception {
    server.reply(200, CHUNKS).reply(200, "{\"entries\":[{\"id\":\"e1\"}]}");
    MemoryClient client = client().build();

    client.fetchEpisodicMemories(SearchEpisodicRequest.builder("q").sessionId("s1").build());
    List<JsonNode> entries = client.listEpisodic(ListEpisodicRequest.builder().userId("u").build());

    assertEquals("s1", server.requests().get(0).json().get("session_id").asText());
    assertEquals("org_id=&project_id=&user_id=u&limit=20", server.requests().get(1).query());
    assertEquals(1, entries.size());
  }

  // --- Taxonomic memory -----------------------------------------------------------------------

  @Test
  void createTaxonomicDefaultsToOrgVisibilityWithExpansion() throws Exception {
    server.reply(200, "{\"id\":\"1\",\"domain\":\"d\",\"term\":\"t\",\"has_embedding\":false,\"acknowledged\":true}");

    client().build().createTaxonomic(CreateTaxonomicRequest.builder("d", "t", "def").userId("u").build());

    JsonNode body = server.only().json();
    assertEquals("org", body.get("visibility").asText());
    assertTrue(body.get("query_expansion").asBoolean());
  }

  @Test
  void getTaxonomicPicksTheMatchingTerm() {
    server.reply(200, "{\"entries\":[{\"term\":\"a\"},{\"term\":\"b\"}]}").reply(200, "{\"entries\":[{\"term\":\"a\"}]}");
    MemoryClient client = client().build();

    var b = client.getTaxonomic(GetTaxonomicRequest.builder().domain("d").term("b").build());
    var first = client.getTaxonomic(GetTaxonomicRequest.builder().domain("d").build());
    server.reply(200, "{\"entries\":[{\"term\":\"a\"}]}");
    var missing = client.getTaxonomic(GetTaxonomicRequest.builder().domain("d").term("zzz").build());

    assertEquals("b", b.orElseThrow().get("term").asText());
    assertEquals("a", first.orElseThrow().get("term").asText());
    assertTrue(missing.isEmpty());
  }

  @Test
  void taxonomicSearchIgnoresUserScope() throws Exception {
    server.reply(200, CHUNKS);

    client().build().fetchTaxonomicMemories(SearchTaxonomicRequest.builder("q").domain("d").build());

    assertEquals("/api/v1/memory/retrieval/taxonomic", server.only().path());
    assertFalse(server.only().json().has("user_id"));
  }

  @Test
  void distinctDomainsAreListed() {
    server.reply(200, "{\"domains\":[\"a\",\"b\"]}");

    assertEquals(List.of("a", "b"), client().build().getDistinctDomains("org"));
    assertEquals("org_id=&project_id=&visibility=org", server.only().query());
  }

  // --- Procedural memory ----------------------------------------------------------------------

  @Test
  void createProceduralSendsTheFullProcedure() throws Exception {
    server.reply(200, "{\"id\":\"1\",\"procedure\":\"p\",\"has_embedding\":false,\"acknowledged\":true}");

    client()
        .build()
        .createProcedural(
            CreateProceduralRequest.builder("p", "d", "c").userId("u").steps(List.of(Map.of("n", 1))).build());

    JsonNode body = server.only().json();
    assertEquals("private", body.get("visibility").asText());
    assertEquals(json("[{\"n\":1}]"), body.get("steps"));
  }

  @Test
  void getProceduralByNameFiltersListedEntries() {
    server.reply(200, "{\"entries\":[{\"procedure\":\"x\"},{\"procedure\":\"p\",\"id\":\"2\"}]}");

    var found = client().build().getProcedural(GetProceduralRequest.builder().procedure("p").build());

    assertEquals("2", found.orElseThrow().get("id").asText());
    assertEquals("org_id=&project_id=&include_deleted=false&procedure=p", server.only().query());
  }

  @Test
  void getProceduralByIdUsesTheIdRoute() {
    server.reply(200, "{\"id\":\"7\"}");

    client().build().getProcedural(GetProceduralRequest.builder().id("7").includeDeleted(true).build());

    assertEquals("/api/v1/memory/procedural/7", server.only().path());
    assertEquals("org_id=&project_id=&include_deleted=true", server.only().query());
  }

  @Test
  void updateAndDeleteResolveTheIdFromTheName() throws Exception {
    server
        .reply(200, "{\"entries\":[{\"procedure\":\"p\",\"id\":\"9\"}]}")
        .reply(200, "{\"id\":\"9\"}")
        .reply(200, "{\"entries\":[{\"procedure\":\"p\",\"id\":\"9\"}]}")
        .reply(200, "{\"deleted_count\":1,\"acknowledged\":true}");
    MemoryClient client = client().build();

    client.updateProcedural(UpdateProceduralRequest.builder().procedure("p").description("new").build());
    var deleted = client.deleteProcedural(DeleteProceduralRequest.builder().procedure("p").build());

    var requests = server.requests();
    assertEquals("PATCH", requests.get(1).method());
    assertEquals("/api/v1/memory/procedural/9", requests.get(1).path());
    assertEquals("DELETE", requests.get(3).method());
    assertEquals("org_id=&project_id=&soft=true", requests.get(3).query());
    assertEquals(1, deleted.deletedCount());
  }

  @Test
  void updateProceduralNeedsAnIdOrName() {
    assertThrows(
        IllegalArgumentException.class,
        () -> client().build().updateProcedural(UpdateProceduralRequest.builder().build()));
    assertThrows(
        IllegalArgumentException.class,
        () -> client().build().deleteProcedural(DeleteProceduralRequest.builder().build()));
  }

  @Test
  void discoverProceduresFlattensMetadataIntoEachResult() {
    server.reply(
        200,
        "{\"memories\":[{\"content\":\"do it\",\"similarity_score\":0.8,"
            + "\"metadata\":{\"procedure\":\"deploy\",\"tags\":[\"ops\"]}},{\"content\":\"bare\"}]}");

    List<Map<String, Object>> found =
        client().build().discoverProcedures(SearchProceduralRequest.builder("how to deploy").build());

    JsonNode body = server.only().json();
    assertEquals(10, body.get("top_k").asInt());
    assertEquals(0.0, body.get("similarity_threshold").asDouble());
    assertEquals(
        Map.of("procedure", "deploy", "content", "do it", "score", 0.8, "tags", List.of("ops")), found.get(0));
    assertEquals(Map.of("procedure", "", "content", "bare", "score", 0.0), found.get(1));
  }

  @Test
  void fetchProceduralMemoriesDefaultsToFiftyResults() {
    server.reply(200, CHUNKS);

    client().build().fetchProceduralMemories(SearchProceduralRequest.builder("q").build());

    assertEquals(50, server.only().json().get("top_k").asInt());
  }

  // --- Custom memory types --------------------------------------------------------------------

  @Test
  void customTypesUseTheTypeRoutes() throws Exception {
    server
        .reply(200, "{\"id\":\"1\",\"type\":\"note\",\"tags\":{\"team\":\"a\",\"p\":2},\"has_embedding\":true}")
        .reply(
            200,
            "{\"results\":[{\"id\":\"1\",\"type\":\"note\",\"content\":\"c\",\"tags\":{\"team\":\"a\"},"
                + "\"contextual_metadata\":null}],\"count\":1}");
    MemoryClient client = client().build();

    var saved = client.createCustom(CreateCustomRequest.builder("note", "hello").tags(Map.of("team", "a")).build());
    var found = client.retrieveCustom(RetrieveCustomRequest.builder("note", "hel").build());

    var requests = server.requests();
    assertEquals("/api/v1/memory/types/note", requests.get(0).path());
    assertEquals(json("{\"content\":\"hello\",\"tags\":{\"team\":\"a\"}}"), requests.get(0).json());
    assertEquals("/api/v1/memory/types/note/retrieve", requests.get(1).path());
    assertEquals(10, requests.get(1).json().get("top_k").asInt());
    assertEquals(Map.of("team", "a", "p", 2), saved.tags());
    assertEquals(1, found.count());
    assertNull(found.results().get(0).contextualMetadata());
  }

  // --- Transport ------------------------------------------------------------------------------

  @Test
  void headersAreStaticPlusDynamic() {
    server.reply(200, "{\"domains\":[]}");
    AtomicInteger calls = new AtomicInteger();

    client()
        .staticHeaders(Map.of("Authorization", "Bearer t", "X-Agent-Id", "a"))
        .dynamicHeaders(() -> Map.of("X-Agent-Id", "override-" + calls.incrementAndGet()))
        .build()
        .getDistinctDomains(null);

    assertEquals("Bearer t", server.only().header("Authorization"));
    assertEquals("override-1", server.only().header("X-Agent-Id"));
  }

  @Test
  void transientFailuresAreRetriedWhenEnabled() {
    server.reply(503, "busy").reply(502, "bad gateway").reply(200, "{\"domains\":[\"a\"]}");

    List<String> domains = client().maxRetries(2).build().getDistinctDomains(null);

    assertEquals(List.of("a"), domains);
    assertEquals(3, server.requests().size());
  }

  @Test
  void retriesStopAtTheLimitAndSurfaceTheLastStatus() {
    server.reply(503, "one").reply(503, "two").reply(503, "three");

    HttpStatusException error =
        assertThrows(HttpStatusException.class, () -> client().maxRetries(1).build().getDistinctDomains(null));

    assertEquals("two", error.responseText());
    assertEquals(2, server.requests().size());
  }

  @Test
  void clientErrorsAndDefaultConfigurationDoNotRetry() {
    server.reply(503, "busy").reply(200, "{\"domains\":[]}");

    assertThrows(HttpStatusException.class, () -> client().build().getDistinctDomains(null));
    assertEquals(1, server.requests().size());
  }

  @Test
  void plainHttpRequestsDoNotAskForAnH2cUpgrade() {
    server.reply(200, "{\"domains\":[]}");

    client().build().getDistinctDomains(null);

    assertNull(server.only().header("Upgrade"));
    assertNull(server.only().header("HTTP2-Settings"));
  }

  @Test
  void clientErrorsAreNotRetried() {
    server.reply(400, "bad").reply(200, "{\"domains\":[]}");

    HttpStatusException error =
        assertThrows(HttpStatusException.class, () -> client().maxRetries(3).build().getDistinctDomains(null));

    assertEquals(400, error.status());
    assertEquals(1, server.requests().size());
  }

  @Test
  void networkFailuresAreRetriedThenThrown() {
    server.close();

    assertThrows(
        UncheckedIOException.class,
        () -> client().maxRetries(1).timeout(Duration.ofSeconds(2)).build().getDistinctDomains(null));
  }

  @Test
  void unreadableSuccessBodyIsAResponseFormatError() {
    server.reply(200, "<html>").reply(200, "{\"memories\":[{\"id\":\"m\"}]}");
    MemoryClient client = client().build();

    assertThrows(ResponseFormatException.class, () -> client.getDistinctDomains(null));
    assertThrows(
        ResponseFormatException.class,
        () -> client.fetchSemanticMemories(SearchSemanticRequest.builder("q").build()));
  }

  @Test
  void dotSegmentIdsAreRefused() {
    assertThrows(
        IllegalArgumentException.class,
        () -> client().build().getSemantic(GetSemanticRequest.builder().id("..").build()));
    assertThrows(
        IllegalArgumentException.class,
        () -> client().build().retrieveCustom(RetrieveCustomRequest.builder("..", "q").build()));
    assertTrue(server.requests().isEmpty());
  }

  @Test
  void requestFieldsAreDefensivelyCopied() {
    List<String> tags = new java.util.ArrayList<>(List.of("a"));
    var request = CreateEpisodicRequest.builder("t", "c").tags(tags).build();

    tags.add("b");

    assertEquals(List.of("a"), request.tags());
    assertThrows(UnsupportedOperationException.class, () -> request.tags().add("c"));
  }

  @Test
  void toBuilderRoundTripsARequest() {
    var request = SearchEpisodicRequest.builder("q").userId("u").sessionId("s").visibility("org").topK(5).build();

    assertEquals(request, request.toBuilder().build());
    assertEquals("u2", request.toBuilder().userId("u2").build().userId());
  }
}
