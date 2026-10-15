package com.mongodb.agentengine.sdk.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.agentengine.sdk.api.v1.memory.CreateCustomRequest;
import com.mongodb.agentengine.sdk.api.v1.memory.CreateEpisodicRequest;
import com.mongodb.agentengine.sdk.api.v1.memory.CreateEpisodicResult;
import com.mongodb.agentengine.sdk.api.v1.memory.CreateProceduralRequest;
import com.mongodb.agentengine.sdk.api.v1.memory.CreateProceduralResult;
import com.mongodb.agentengine.sdk.api.v1.memory.CreateSemanticRequest;
import com.mongodb.agentengine.sdk.api.v1.memory.CreateSemanticResult;
import com.mongodb.agentengine.sdk.api.v1.memory.CreateTaxonomicRequest;
import com.mongodb.agentengine.sdk.api.v1.memory.CreateTaxonomicResult;
import com.mongodb.agentengine.sdk.api.v1.memory.CustomMemoryRetrieveResult;
import com.mongodb.agentengine.sdk.api.v1.memory.CustomMemorySaveResult;
import com.mongodb.agentengine.sdk.api.v1.memory.GetProceduralRequest;
import com.mongodb.agentengine.sdk.api.v1.memory.GetSemanticRequest;
import com.mongodb.agentengine.sdk.api.v1.memory.GetTaxonomicRequest;
import com.mongodb.agentengine.sdk.api.v1.memory.ListEpisodicRequest;
import com.mongodb.agentengine.sdk.api.v1.memory.RetrieveCustomRequest;
import java.util.List;
import java.util.Optional;

/**
 * The seam the facade's type-specific save, get and list operations delegate to.
 *
 * <p>Like {@link MemoryRuntime}, requests arrive with identity resolved and tenancy is internal.
 * Custom-type operations carry no identity: the platform stamps it.
 */
public interface MemoryCrudClient extends AutoCloseable {

  CreateSemanticResult createSemantic(CreateSemanticRequest request);

  Optional<JsonNode> getSemantic(GetSemanticRequest request);

  CreateEpisodicResult createEpisodic(CreateEpisodicRequest request);

  List<JsonNode> listEpisodic(ListEpisodicRequest request);

  CreateTaxonomicResult createTaxonomic(CreateTaxonomicRequest request);

  Optional<JsonNode> getTaxonomic(GetTaxonomicRequest request);

  List<String> getDistinctDomains(String visibility);

  CreateProceduralResult createProcedural(CreateProceduralRequest request);

  Optional<JsonNode> getProcedural(GetProceduralRequest request);

  CustomMemorySaveResult createCustom(CreateCustomRequest request);

  CustomMemoryRetrieveResult retrieveCustom(RetrieveCustomRequest request);

  /** Releases underlying resources. The default does nothing. */
  @Override
  default void close() {}
}
