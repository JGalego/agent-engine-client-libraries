package com.mongodb.agentengine.sdk.memory;

import com.mongodb.agentengine.sdk.api.v1.memory.BuildContextRequest;
import com.mongodb.agentengine.sdk.api.v1.memory.ContextResponse;
import com.mongodb.agentengine.sdk.api.v1.memory.MemoryChunk;
import com.mongodb.agentengine.sdk.api.v1.memory.SearchEpisodicRequest;
import com.mongodb.agentengine.sdk.api.v1.memory.SearchProceduralRequest;
import com.mongodb.agentengine.sdk.api.v1.memory.SearchSemanticRequest;
import com.mongodb.agentengine.sdk.api.v1.memory.SearchTaxonomicRequest;
import com.mongodb.agentengine.sdk.api.v1.memory.WriteTurnRequest;
import com.mongodb.agentengine.sdk.api.v1.memory.WriteTurnResult;
import java.util.List;
import java.util.Map;

/**
 * The transport seam every memory backend implements for the conversation loop and ranked search.
 *
 * <p>Requests arrive with their identity already resolved. Tenancy is internal to each backend.
 */
public interface MemoryRuntime extends AutoCloseable {

  WriteTurnResult recordTurn(WriteTurnRequest request);

  ContextResponse buildContext(BuildContextRequest request);

  List<MemoryChunk> searchSemantic(SearchSemanticRequest request);

  List<MemoryChunk> searchEpisodes(SearchEpisodicRequest request);

  List<MemoryChunk> searchTaxonomic(SearchTaxonomicRequest request);

  List<Map<String, Object>> discoverProcedures(SearchProceduralRequest request);

  /** Releases underlying resources. The default does nothing. */
  @Override
  default void close() {}
}
