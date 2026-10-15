package com.mongodb.agentengine.sdk.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.mongodb.agentengine.sdk.api.v1.memory.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** A runtime and CRUD client that records every request and answers with canned results. */
class RecordingBackend implements MemoryRuntime, MemoryCrudClient {

  final List<Object> requests = new ArrayList<>();
  List<MemoryChunk> semantic = List.of();
  List<MemoryChunk> episodic = List.of();
  List<MemoryChunk> taxonomic = List.of();
  List<Map<String, Object>> procedures = List.of();
  boolean closed;

  Object last() {
    return requests.get(requests.size() - 1);
  }

  @SuppressWarnings("unchecked")
  <T> T last(Class<T> type) {
    return (T) type.cast(last());
  }

  static MemoryChunk chunk(String id, Double score) {
    return new MemoryChunk(id, "content " + id, MemorySource.SEMANTIC, Instant.EPOCH, null, score, null);
  }

  @Override
  public WriteTurnResult recordTurn(WriteTurnRequest r) {
    requests.add(r);
    return new WriteTurnResult("t", "s", 1, true);
  }

  @Override
  public ContextResponse buildContext(BuildContextRequest r) {
    requests.add(r);
    return new ContextResponse(JsonNodeFactory.instance.textNode("ctx"), null, null);
  }

  @Override
  public List<MemoryChunk> searchSemantic(SearchSemanticRequest r) {
    requests.add(r);
    return semantic;
  }

  @Override
  public List<MemoryChunk> searchEpisodes(SearchEpisodicRequest r) {
    requests.add(r);
    return episodic;
  }

  @Override
  public List<MemoryChunk> searchTaxonomic(SearchTaxonomicRequest r) {
    requests.add(r);
    return taxonomic;
  }

  @Override
  public List<Map<String, Object>> discoverProcedures(SearchProceduralRequest r) {
    requests.add(r);
    return procedures;
  }

  @Override
  public CreateSemanticResult createSemantic(CreateSemanticRequest r) {
    requests.add(r);
    return new CreateSemanticResult("1", r.label(), false, true);
  }

  @Override
  public Optional<JsonNode> getSemantic(GetSemanticRequest r) {
    requests.add(r);
    return Optional.empty();
  }

  @Override
  public CreateEpisodicResult createEpisodic(CreateEpisodicRequest r) {
    requests.add(r);
    return new CreateEpisodicResult("1", r.title(), false, true);
  }

  @Override
  public List<JsonNode> listEpisodic(ListEpisodicRequest r) {
    requests.add(r);
    return List.of();
  }

  @Override
  public CreateTaxonomicResult createTaxonomic(CreateTaxonomicRequest r) {
    requests.add(r);
    return new CreateTaxonomicResult("1", r.domain(), r.term(), false, true);
  }

  @Override
  public Optional<JsonNode> getTaxonomic(GetTaxonomicRequest r) {
    requests.add(r);
    return Optional.empty();
  }

  @Override
  public List<String> getDistinctDomains(String visibility) {
    requests.add("domains:" + visibility);
    return List.of("d");
  }

  @Override
  public CreateProceduralResult createProcedural(CreateProceduralRequest r) {
    requests.add(r);
    return new CreateProceduralResult("1", r.procedure(), false, true);
  }

  @Override
  public Optional<JsonNode> getProcedural(GetProceduralRequest r) {
    requests.add(r);
    return Optional.empty();
  }

  @Override
  public CustomMemorySaveResult createCustom(CreateCustomRequest r) {
    requests.add(r);
    return new CustomMemorySaveResult("1", r.memoryType(), Map.of(), false);
  }

  @Override
  public CustomMemoryRetrieveResult retrieveCustom(RetrieveCustomRequest r) {
    requests.add(r);
    return new CustomMemoryRetrieveResult(List.of(), 0);
  }

  @Override
  public void close() {
    closed = true;
  }
}
