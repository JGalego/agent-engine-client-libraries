package com.mongodb.agentengine.sdk.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mongodb.agentengine.sdk.api.v1.memory.*;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MemoryTest {

  /** A backend that reports an ambient identity, as app-bound runtimes do. */
  private static final class AmbientBackend extends RecordingBackend implements AmbientIdentityRuntime {
    private final MemoryRequestContext ambient;

    AmbientBackend(MemoryRequestContext ambient) {
      this.ambient = ambient;
    }

    @Override
    public MemoryRequestContext requestContext() {
      return ambient;
    }
  }

  private final RecordingBackend backend = new RecordingBackend();
  private final Memory memory = Memory.of(backend, backend);

  // --- Identity and defaults ----------------------------------------------------------------

  @Test
  void saveSemanticResolvesTheUserAndUpsertsByDefault() {
    memory.bind(MemoryRequestContext.of("bound-user", null)).saveSemantic(CreateSemanticRequest.builder("l", "t").build());

    var sent = backend.last(CreateSemanticRequest.class);
    assertEquals("bound-user", sent.userId());
    assertEquals(Boolean.TRUE, sent.upsert());
  }

  @Test
  void saveSemanticKeepsAnExplicitUpsertFlag() {
    memory.saveSemantic(CreateSemanticRequest.builder("l", "t").userId("u").upsert(false).build());

    assertEquals(Boolean.FALSE, backend.last(CreateSemanticRequest.class).upsert());
  }

  @Test
  void saveRequiresAUser() {
    assertThrows(MemoryIdentityException.class, () -> memory.saveSemantic(CreateSemanticRequest.builder("l", "t").build()));
    assertThrows(MemoryIdentityException.class, () -> memory.saveTaxonomic(CreateTaxonomicRequest.builder("d", "t", "x").build()));
    assertThrows(MemoryIdentityException.class, () -> memory.saveProcedure(CreateProceduralRequest.builder("p", "d", "c").build()));
    assertTrue(backend.requests.isEmpty());
  }

  @Test
  void saveEpisodeRequiresUserAndSession() {
    var request = CreateEpisodicRequest.builder("t", "c").userId("u").build();

    assertThrows(MemoryIdentityException.class, () -> memory.saveEpisode(request));
    memory.bind(MemoryRequestContext.of(null, "s1")).saveEpisode(request);

    assertEquals("s1", backend.last(CreateEpisodicRequest.class).sessionId());
  }

  @Test
  void callArgumentBeatsBoundContext() {
    memory.bind(MemoryRequestContext.of("bound", "bound-session"))
        .recordTurn(WriteTurnRequest.builder("user").userId("call").build());

    var sent = backend.last(WriteTurnRequest.class);
    assertEquals("call", sent.userId());
    assertEquals("bound-session", sent.sessionId());
  }

  @Test
  void recordTurnGeneratesAnIdempotencyKeyUnlessGiven() {
    memory.recordTurn(WriteTurnRequest.builder("user").build());
    String generated = backend.last(WriteTurnRequest.class).idempotencyKey();
    memory.recordTurn(WriteTurnRequest.builder("user").build());
    String another = backend.last(WriteTurnRequest.class).idempotencyKey();
    memory.recordTurn(WriteTurnRequest.builder("user").idempotencyKey("mine").build());

    assertFalse(generated.isBlank());
    assertFalse(generated.equals(another));
    assertEquals("mine", backend.last(WriteTurnRequest.class).idempotencyKey());
  }

  @Test
  void bindDoesNotChangeTheOriginalFacade() {
    memory.bind(MemoryRequestContext.of("bound", null));

    memory.recordTurn(WriteTurnRequest.builder("user").build());

    assertNull(backend.last(WriteTurnRequest.class).userId());
  }

  @Test
  void aBroadVisibilityDoesNotBorrowTheAmbientUser() {
    var ambient = new AmbientBackend(MemoryRequestContext.of("ambient", null));
    Memory facade = Memory.of(ambient, ambient);

    facade.searchSemantic(SearchSemanticRequest.builder("q").build());
    assertEquals("ambient", ambient.last(SearchSemanticRequest.class).userId());

    facade.searchSemantic(SearchSemanticRequest.builder("q").visibility("org").build());
    assertNull(ambient.last(SearchSemanticRequest.class).userId());
  }

  @Test
  void aUserThatContradictsTheAmbientIdentityIsRefused() {
    var ambient = new AmbientBackend(MemoryRequestContext.of("ambient", null));
    Memory facade = Memory.of(ambient, ambient);

    assertThrows(
        MemoryIdentityException.class,
        () -> facade.searchSemantic(SearchSemanticRequest.builder("q").userId("someone-else").build()));
    assertThrows(
        MemoryIdentityException.class,
        () -> facade.bind(MemoryRequestContext.of("someone-else", null)).saveSemantic(CreateSemanticRequest.builder("l", "t").build()));
    assertTrue(ambient.requests.isEmpty());

    facade.saveSemantic(CreateSemanticRequest.builder("l", "t").userId("ambient").build());
    assertEquals("ambient", ambient.last(CreateSemanticRequest.class).userId());
  }

  @Test
  void episodicReadsIgnoreAnInheritedSession() {
    var bound = memory.bind(MemoryRequestContext.of("u", "bound-session"));

    bound.searchEpisodes(SearchEpisodicRequest.builder("q").build());
    assertNull(backend.last(SearchEpisodicRequest.class).sessionId());
    bound.listEpisodes();
    assertNull(backend.last(ListEpisodicRequest.class).sessionId());
    bound.searchEpisodes(SearchEpisodicRequest.builder("q").sessionId("explicit").build());
    assertEquals("explicit", backend.last(SearchEpisodicRequest.class).sessionId());
  }

  @Test
  void buildContextInheritsTheBoundSession() {
    memory.bind(MemoryRequestContext.of("u", "s")).buildContext(BuildContextRequest.builder("q").maxTokens(1000).build());

    var sent = backend.last(BuildContextRequest.class);
    assertEquals("s", sent.sessionId());
    assertEquals(1000, sent.maxTokens());
  }

  // --- Injected runtimes ----------------------------------------------------------------------

  @Test
  void aRuntimeWithoutACrudClientCannotSave() {
    Memory runtimeOnly = Memory.of(backend);

    runtimeOnly.buildContext(BuildContextRequest.builder("q").build());
    assertThrows(
        MemoryNotSupportedException.class,
        () -> runtimeOnly.saveSemantic(CreateSemanticRequest.builder("l", "t").userId("u").build()));
    assertThrows(MemoryNotSupportedException.class, runtimeOnly::listDomains);
  }

  @Test
  void injectedBackendsAreNotClosedByTheFacade() {
    memory.close();
    memory.bind(MemoryRequestContext.EMPTY).close();

    assertFalse(backend.closed);
  }

  // --- Custom types ---------------------------------------------------------------------------

  @Test
  void customTypesAreValidatedBeforeAnyRequest() {
    assertThrows(MemoryClientException.class, () -> memory.save(CreateCustomRequest.builder("semantic", "c").build()));
    assertThrows(
        MemoryClientException.class,
        () -> memory.save(CreateCustomRequest.builder("note", "c").tags(Map.of("team", " ")).build()));
    assertThrows(MemoryClientException.class, () -> memory.retrieve(RetrieveCustomRequest.builder("Bad", "q").build()));
    assertTrue(backend.requests.isEmpty());
  }

  @Test
  void validCustomRequestsPassThroughUnchanged() {
    var request = CreateCustomRequest.builder("note", "c").tags(Map.of("team", "a")).build();

    memory.save(request);

    assertEquals(request, backend.last());
  }

  // --- Unified search -------------------------------------------------------------------------

  @Test
  void searchDefaultsToSemanticAndEpisodic() {
    memory.search(SearchRequest.builder("q").build());

    assertEquals(2, backend.requests.size());
    assertTrue(backend.requests.get(0) instanceof SearchSemanticRequest);
    assertTrue(backend.requests.get(1) instanceof SearchEpisodicRequest);
  }

  @Test
  void searchMergesRanksAndTruncates() {
    backend.semantic = List.of(RecordingBackend.chunk("low", 0.2), RecordingBackend.chunk("unscored", null));
    backend.episodic = List.of(RecordingBackend.chunk("high", 0.9), RecordingBackend.chunk("mid", 0.5));

    var hits = memory.search(SearchRequest.builder("q").topK(3).build());

    assertEquals(List.of("high", "mid", "low"), hits.stream().map(MemoryChunk::id).toList());
    assertEquals(3, backend.last(SearchEpisodicRequest.class).topK());
  }

  @Test
  void unscoredChunksRankLastKeepingTheirOrder() {
    backend.semantic = List.of(RecordingBackend.chunk("a", null), RecordingBackend.chunk("b", 0.1), RecordingBackend.chunk("c", null));

    var hits = memory.search(SearchRequest.builder("q").sources(Set.of(SearchSource.SEMANTIC)).build());

    assertEquals(List.of("b", "a", "c"), hits.stream().map(MemoryChunk::id).toList());
  }

  @Test
  void searchAdaptsProceduresToChunks() {
    backend.procedures =
        List.of(
            Map.of("procedure", "deploy", "content", "do it", "score", 0.7, "id", "p1", "created_at", "2026-01-02T03:04:05Z"),
            Map.of("procedure", "bare"));

    var hits =
        memory.search(
            SearchRequest.builder("q")
                .sources(new LinkedHashSet<>(List.of(SearchSource.PROCEDURAL)))
                .tags(List.of("ops"))
                .similarityThreshold(0.3)
                .build());

    var request = backend.last(SearchProceduralRequest.class);
    assertEquals(List.of("ops"), request.tags());
    assertEquals(0.3, request.similarityThreshold());
    MemoryChunk first = hits.get(0);
    assertEquals("p1", first.id());
    assertEquals("do it", first.content());
    assertEquals(MemorySource.PROCEDURAL, first.source());
    assertEquals(0.7, first.similarityScore());
    assertEquals("2026-01-02T03:04:05Z", first.timestamp().toString());
    MemoryChunk bare = hits.get(1);
    assertEquals("", bare.id());
    assertEquals("bare", bare.content());
    assertNull(bare.similarityScore());
    assertEquals(Instant.EPOCH, bare.timestamp());
  }

  @Test
  void searchLeavesSessionOnlyOnTheEpisodicLegAndTaxonomicUnscoped() {
    memory
        .bind(MemoryRequestContext.of("u", "bound"))
        .search(
            SearchRequest.builder("q")
                .sources(Set.of(SearchSource.EPISODIC, SearchSource.TAXONOMIC))
                .sessionId("explicit")
                .domain("d")
                .build());

    SearchEpisodicRequest episodic = null;
    SearchTaxonomicRequest taxonomic = null;
    for (Object request : backend.requests) {
      if (request instanceof SearchEpisodicRequest e) episodic = e;
      if (request instanceof SearchTaxonomicRequest t) taxonomic = t;
    }
    assertEquals("explicit", episodic.sessionId());
    assertEquals("u", episodic.userId());
    assertEquals("d", taxonomic.domain());
  }
}
