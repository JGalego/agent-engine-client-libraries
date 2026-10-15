package com.mongodb.agentengine.sdk.clients;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mongodb.agentengine.sdk.Json;
import com.mongodb.agentengine.sdk.api.v1.memory.BuildContextRequest;
import com.mongodb.agentengine.sdk.api.v1.memory.ContextResponse;
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
import com.mongodb.agentengine.sdk.api.v1.memory.DeleteProceduralRequest;
import com.mongodb.agentengine.sdk.api.v1.memory.DeleteResult;
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
import com.mongodb.agentengine.sdk.api.v1.memory.WriteTurnResult;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * HTTP client for the Memory Server API.
 *
 * <p>Requests carry the client's {@code orgId} and {@code projectId}. They default to empty, which
 * lets the backend stamp the trusted tenancy from the execution context or the service-account
 * token.
 *
 * <p>Failed requests throw {@link HttpStatusException}, unreadable success bodies throw {@link
 * ResponseFormatException}, and network failures throw {@link UncheckedIOException}. Lookups
 * return {@link Optional#empty()} on a 404.
 *
 * <p>Instances are immutable and thread-safe.
 *
 * <pre>{@code
 * MemoryClient client = MemoryClient.builder("http://127.0.0.1:8081").build();
 * client.writeTurn(WriteTurnRequest.builder("user").content("Hello").sessionId("thread_123").build());
 * ContextResponse context =
 *     client.buildContext(BuildContextRequest.builder("What did we discuss?").sessionId("thread_123").build());
 * }</pre>
 */
public final class MemoryClient {

  private static final Set<Integer> RETRYABLE_STATUS = Set.of(502, 503, 504);
  private static final long RETRY_BASE_DELAY_MS = 200;

  private final String baseUrl;
  private final String orgId;
  private final String projectId;
  private final Duration timeout;
  private final Map<String, String> staticHeaders;
  private final Supplier<Map<String, String>> dynamicHeaders;
  private final String apiPrefix;
  private final int maxRetries;
  private final MemoryRouteStyle routeStyle;
  private final HttpClient http;

  private MemoryClient(Builder b) {
    this.baseUrl = Http.trimTrailingSlash(b.baseUrl);
    this.orgId = b.orgId;
    this.projectId = b.projectId;
    this.timeout = b.timeout;
    this.staticHeaders = Map.copyOf(b.staticHeaders);
    this.dynamicHeaders = b.dynamicHeaders;
    this.apiPrefix = "/" + b.apiPrefix.replaceAll("^/+|/+$", "");
    this.maxRetries = Math.max(0, b.maxRetries);
    this.routeStyle = b.routeStyle;
    this.http = b.http != null ? b.http : HttpClient.newBuilder().connectTimeout(b.timeout).build();
  }

  /** Starts a client for the memory server (or proxy) at {@code baseUrl}. */
  public static Builder builder(String baseUrl) {
    return new Builder(baseUrl);
  }

  /** Builder for {@link MemoryClient}. */
  public static final class Builder {
    private final String baseUrl;
    private String orgId = "";
    private String projectId = "";
    private Duration timeout = Duration.ofSeconds(30);
    private Map<String, String> staticHeaders = Map.of();
    private Supplier<Map<String, String>> dynamicHeaders;
    private String apiPrefix = "/api/v1/memory";
    private int maxRetries;
    private MemoryRouteStyle routeStyle = MemoryRouteStyle.NATIVE;
    private HttpClient http;

    private Builder(String baseUrl) {
      this.baseUrl = baseUrl;
    }

    /** Tenant org sent with every request. Default: empty, so the backend stamps it. */
    public Builder orgId(String v) { orgId = v; return this; }

    /** Tenant project sent with every request. Default: empty, so the backend stamps it. */
    public Builder projectId(String v) { projectId = v; return this; }

    /** Per-request timeout. Default: 30 seconds. */
    public Builder timeout(Duration v) { timeout = v; return this; }

    /** Headers sent with every request, such as identity or authorization headers. */
    public Builder staticHeaders(Map<String, String> v) { staticHeaders = v; return this; }

    /**
     * Headers computed on every request and merged over the static ones. Use it for values that
     * change per execution.
     */
    public Builder dynamicHeaders(Supplier<Map<String, String>> v) { dynamicHeaders = v; return this; }

    /**
     * Path prefix of the memory endpoints. Default: {@code /api/v1/memory}, the orchestration
     * engine proxy route. Use {@code /api/v1} when connecting to the memory server directly.
     */
    public Builder apiPrefix(String v) { apiPrefix = v; return this; }

    /**
     * Retries on 502, 503, 504 and network errors, with exponential backoff. Default: 0. Retried
     * writes can duplicate, so set it only on clients that issue idempotent requests.
     */
    public Builder maxRetries(int v) { maxRetries = v; return this; }

    /** The route convention the backend speaks. Default: {@link MemoryRouteStyle#NATIVE}. */
    public Builder routeStyle(MemoryRouteStyle v) { routeStyle = v; return this; }

    /** The underlying HTTP client, for custom TLS, proxies or tests. */
    public Builder httpClient(HttpClient v) { http = v; return this; }

    public MemoryClient build() {
      return new MemoryClient(this);
    }
  }

  // ===========================================================================
  // Short-term memory
  // ===========================================================================

  /** Appends a conversation turn. */
  public WriteTurnResult writeTurn(WriteTurnRequest r) {
    Body body =
        new Body()
            .putNullable("session_id", normalizeSessionId(r.sessionId()))
            .put("role", r.role())
            .put("org_id", orgId)
            .putNullable("user_id", r.userId())
            .put("project_id", projectId)
            .put("content", r.content())
            .put("tokens", r.tokens())
            .put("stop_reason", r.stopReason())
            .put("model_name", r.modelName())
            .put("tool_calls", r.toolCalls())
            .put("tool_call_id", r.toolCallId())
            .put("tool_name", r.toolName())
            .put("is_error", orDefault(r.isError(), false))
            .put("idempotency_key", r.idempotencyKey());
    return post(turnsPath(), body, WriteTurnResult.class);
  }

  // ===========================================================================
  // Context building
  // ===========================================================================

  /** Builds a unified memory context. */
  public ContextResponse buildContext(BuildContextRequest r) {
    Body body =
        new Body()
            .put("query", r.query())
            .putNullable("session_id", normalizeSessionId(r.sessionId()))
            .put("org_id", orgId)
            .putNullable("user_id", r.userId())
            .put("visibility", r.visibility())
            .put("project_id", projectId)
            .put("enabled_sources", sortedWireValues(r.enabledSources()))
            .put("format_style", r.formatStyle())
            .put("top_k", orDefault(r.topK(), 50));
    if (r.metadataFilter() != null && !r.metadataFilter().isEmpty()) {
      body.put("metadata_filter", r.metadataFilter());
    }
    body.put("max_tokens", r.maxTokens());
    return post(contextPath(), body, ContextResponse.class);
  }

  // ===========================================================================
  // Semantic memory
  // ===========================================================================

  public CreateSemanticResult createSemantic(CreateSemanticRequest r) {
    Body body =
        new Body()
            .put("label", r.label())
            .put("text", r.text())
            .put("org_id", orgId)
            .put("user_id", required(r.userId(), "userId"))
            .put("source", orDefault(r.source(), "agent"))
            .put("visibility", orDefault(r.visibility(), "private"))
            .put("project_id", projectId)
            .put("agent_id", r.agentId())
            .put("embedding", r.embedding())
            .put("metadata", r.metadata())
            .put("upsert", orDefault(r.upsert(), false));
    return post("/semantic", body, CreateSemanticResult.class);
  }

  public List<MemoryChunk> fetchSemanticMemories(SearchSemanticRequest r) {
    Body body =
        new Body()
            .put("query", r.query())
            .put("org_id", orgId)
            .put("project_id", projectId)
            .put("user_id", r.userId())
            .put("visibility", r.visibility())
            .put("top_k", orDefault(r.topK(), 50));
    return search("semantic", body);
  }

  /** Looks up one semantic memory by id, or else by label (the first match, if several). */
  public Optional<JsonNode> getSemantic(GetSemanticRequest r) {
    Map<String, String> params = tenancy();
    if (r.id() != null) {
      params.put("user_id", r.userId());
      params.put("visibility", r.visibility());
      return getOrEmpty("/semantic/" + PathSegments.encode(r.id()), params);
    }
    params.put("user_id", r.userId());
    params.put("visibility", r.visibility());
    params.put("label", r.label());
    return firstEntry("/semantic", params);
  }

  /** Updates the semantic memory with the given label. */
  public JsonNode updateSemantic(UpdateSemanticRequest r) {
    JsonNode existing =
        getSemantic(GetSemanticRequest.builder().label(r.label()).build())
            .orElseThrow(() -> new NoSuchElementException("Semantic memory not found: " + r.label()));
    String id = memoryId(existing, "Semantic memory", r.label());
    Body body =
        new Body()
            .put("org_id", orgId)
            .put("project_id", projectId)
            .put("text", r.text())
            .put("source", r.source())
            .put("visibility", r.visibility());
    return send("PATCH", "/semantic/" + PathSegments.encode(id), Map.of(), body).parse(JsonNode.class);
  }

  // ===========================================================================
  // Episodic memory
  // ===========================================================================

  public CreateEpisodicResult createEpisodic(CreateEpisodicRequest r) {
    Body body =
        new Body()
            .put("title", r.title())
            .put("content", r.content())
            .put("summary_text", orDefault(r.summaryText(), ""))
            .put("org_id", orgId)
            .put("user_id", required(r.userId(), "userId"))
            .put("session_id", requireSessionId(r.sessionId()))
            .put("visibility", orDefault(r.visibility(), "private"))
            .put("project_id", projectId)
            .put("agent_id", r.agentId())
            .put("metadata", r.metadata())
            .put("snapshot_ref_id", r.snapshotRefId())
            .put("summary_type", r.summaryType())
            .put("source_agent", r.sourceAgent())
            .put("participants", r.participants())
            .put("tags", r.tags())
            .put("extraction_source", r.extractionSource())
            .put("embedding", r.embedding())
            .put("skip_embedding", r.skipEmbedding());
    return post("/episodic", body, CreateEpisodicResult.class);
  }

  public List<MemoryChunk> fetchEpisodicMemories(SearchEpisodicRequest r) {
    Body body =
        new Body()
            .put("query", r.query())
            .put("org_id", orgId)
            .put("project_id", projectId)
            .put("user_id", r.userId())
            .put("visibility", r.visibility())
            .put("session_id", r.sessionId())
            .put("top_k", orDefault(r.topK(), 50));
    return search("episodic", body);
  }

  public List<JsonNode> listEpisodic(ListEpisodicRequest r) {
    Map<String, String> params = tenancy();
    params.put("user_id", r.userId());
    params.put("session_id", r.sessionId());
    params.put("visibility", r.visibility());
    params.put("limit", String.valueOf(orDefault(r.limit(), 20)));
    return send("GET", "/episodic", params, null).parse(Entries.class).entries();
  }

  // ===========================================================================
  // Taxonomic memory
  // ===========================================================================

  public CreateTaxonomicResult createTaxonomic(CreateTaxonomicRequest r) {
    Body body =
        new Body()
            .put("domain", r.domain())
            .put("term", r.term())
            .put("definition", r.definition())
            .put("org_id", orgId)
            .put("user_id", required(r.userId(), "userId"))
            .put("project_id", projectId)
            .put("related_terms", r.relatedTerms())
            .put("query_expansion", orDefault(r.queryExpansion(), true))
            .put("visibility", orDefault(r.visibility(), "org"));
    return post("/taxonomic", body, CreateTaxonomicResult.class);
  }

  public List<MemoryChunk> fetchTaxonomicMemories(SearchTaxonomicRequest r) {
    Body body =
        new Body()
            .put("query", r.query())
            .put("org_id", orgId)
            .put("project_id", projectId)
            .put("domain", r.domain())
            .put("visibility", r.visibility())
            .put("top_k", orDefault(r.topK(), 50));
    return search("taxonomic", body);
  }

  /** Looks up a taxonomic entry: the one matching the term if given, else the first in the domain. */
  public Optional<JsonNode> getTaxonomic(GetTaxonomicRequest r) {
    Map<String, String> params = tenancy();
    params.put("domain", r.domain());
    params.put("user_id", r.userId());
    params.put("visibility", r.visibility());
    return firstEntry("/taxonomic", params, "term", r.term());
  }

  public List<String> getDistinctDomains(String visibility) {
    Map<String, String> params = tenancy();
    params.put("visibility", visibility);
    return send("GET", "/taxonomic/domains", params, null).parse(Domains.class).domains();
  }

  // ===========================================================================
  // Procedural memory
  // ===========================================================================

  public CreateProceduralResult createProcedural(CreateProceduralRequest r) {
    Body body =
        new Body()
            .put("procedure", r.procedure())
            .put("description", r.description())
            .put("content", r.content())
            .put("org_id", orgId)
            .put("user_id", required(r.userId(), "userId"))
            .put("steps", r.steps())
            .put("resources", r.resources())
            .put("allowed_tools", r.allowedTools())
            .put("compatibility", r.compatibility())
            .put("license", r.license())
            .put("trigger_conditions", r.triggerConditions())
            .put("tags", r.tags())
            .put("visibility", orDefault(r.visibility(), "private"))
            .put("project_id", projectId)
            .put("agent_id", r.agentId())
            .put("extraction_source", r.extractionSource())
            .put("source_format", r.sourceFormat())
            .put("source_path", r.sourcePath());
    return post("/procedural", body, CreateProceduralResult.class);
  }

  /** Looks up one procedural memory by id, or else by procedure name. */
  public Optional<JsonNode> getProcedural(GetProceduralRequest r) {
    Map<String, String> params = tenancy();
    params.put("include_deleted", String.valueOf(orDefault(r.includeDeleted(), false)));
    params.put("user_id", r.userId());
    params.put("visibility", r.visibility());
    if (r.id() != null) {
      return getOrEmpty("/procedural/" + PathSegments.encode(r.id()), params);
    }
    params.put("procedure", r.procedure());
    return firstEntry("/procedural", params, "procedure", r.procedure());
  }

  public JsonNode updateProcedural(UpdateProceduralRequest r) {
    String id = resolveProceduralId(r.id(), r.procedure(), "update");
    Body body =
        new Body()
            .put("org_id", orgId)
            .put("description", r.description())
            .put("content", r.content())
            .put("steps", r.steps())
            .put("resources", r.resources())
            .put("allowed_tools", r.allowedTools())
            .put("trigger_conditions", r.triggerConditions())
            .put("tags", r.tags())
            .put("visibility", r.visibility())
            .put("project_id", projectId);
    return send("PATCH", "/procedural/" + PathSegments.encode(id), Map.of(), body).parse(JsonNode.class);
  }

  public DeleteResult deleteProcedural(DeleteProceduralRequest r) {
    String id = resolveProceduralId(r.id(), r.procedure(), "delete");
    Map<String, String> params = tenancy();
    params.put("soft", String.valueOf(orDefault(r.soft(), true)));
    return send("DELETE", "/procedural/" + PathSegments.encode(id), params, null).parse(DeleteResult.class);
  }

  /**
   * Finds procedures matching a query. Each result holds the procedure name, its content and the
   * similarity {@code score}, plus every metadata field of the stored procedure.
   */
  public List<Map<String, Object>> discoverProcedures(SearchProceduralRequest r) {
    Body body =
        new Body()
            .put("query", r.query())
            .put("org_id", orgId)
            .put("user_id", r.userId())
            .put("visibility", r.visibility())
            .put("project_id", projectId)
            .put("tags", r.tags())
            .put("top_k", orDefault(r.topK(), 10))
            .put("similarity_threshold", orDefault(r.similarityThreshold(), 0.0));
    if (r.metadataFilter() != null && !r.metadataFilter().isEmpty()) {
      body.put("metadata_filter", r.metadataFilter());
    }
    List<Map<String, Object>> procedures = new ArrayList<>();
    for (JsonNode memory : searchRaw("procedural", body)) {
      Map<String, Object> procedure = new LinkedHashMap<>();
      JsonNode metadata = memory.path("metadata");
      procedure.put("procedure", metadata.path("procedure").asText(""));
      procedure.put("content", memory.path("content").asText(""));
      procedure.put("score", memory.path("similarity_score").asDouble(0.0));
      if (metadata.isObject()) {
        procedure.putAll(Json.mapper().convertValue(metadata, new TypeReference<Map<String, Object>>() {}));
      }
      procedures.add(procedure);
    }
    return procedures;
  }

  public List<MemoryChunk> fetchProceduralMemories(SearchProceduralRequest r) {
    Body body =
        new Body()
            .put("query", r.query())
            .put("org_id", orgId)
            .put("user_id", r.userId())
            .put("visibility", r.visibility())
            .put("project_id", projectId)
            .put("tags", r.tags())
            .put("top_k", orDefault(r.topK(), 50));
    return search("procedural", body);
  }

  // ===========================================================================
  // Custom memory types
  // ===========================================================================

  public CustomMemorySaveResult createCustom(CreateCustomRequest r) {
    Body body =
        new Body()
            .put("content", r.content())
            .put("tags", r.tags())
            .put("contextual_metadata", r.contextualMetadata());
    return post("/types/" + PathSegments.encode(r.memoryType()), body, CustomMemorySaveResult.class);
  }

  public CustomMemoryRetrieveResult retrieveCustom(RetrieveCustomRequest r) {
    Body body =
        new Body()
            .put("query", r.query())
            .put("top_k", orDefault(r.topK(), 10))
            .put("tags", r.tags());
    return post(
        "/types/" + PathSegments.encode(r.memoryType()) + "/retrieve", body, CustomMemoryRetrieveResult.class);
  }

  // ===========================================================================
  // Routes
  // ===========================================================================

  private String turnsPath() {
    return routeStyle == MemoryRouteStyle.ALIASED ? "/turns" : "/stm/turns";
  }

  private String contextPath() {
    return routeStyle == MemoryRouteStyle.ALIASED ? "/context" : "/retrieval/context";
  }

  /** Runs a ranked search; the source is a path segment or a {@code type} body field per style. */
  private List<JsonNode> searchRaw(String type, Body body) {
    if (routeStyle == MemoryRouteStyle.ALIASED) {
      return post("/search", new Body().put("type", type).putAll(body), Memories.class).memories();
    }
    return post("/retrieval/" + type, body, Memories.class).memories();
  }

  private List<MemoryChunk> search(String type, Body body) {
    List<MemoryChunk> chunks = new ArrayList<>();
    for (JsonNode memory : searchRaw(type, body)) {
      try {
        chunks.add(Json.mapper().treeToValue(memory, MemoryChunk.class));
      } catch (JsonProcessingException e) {
        throw new ResponseFormatException("Unexpected memory chunk: " + e.getMessage(), e);
      }
    }
    return chunks;
  }

  private Map<String, String> tenancy() {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("org_id", orgId);
    params.put("project_id", projectId);
    return params;
  }

  // ===========================================================================
  // Requests
  // ===========================================================================

  private <T> T post(String path, Body body, Class<T> type) {
    return send("POST", path, Map.of(), body).parse(type);
  }

  /** GET that maps a 404 to empty and returns the body as-is. */
  private Optional<JsonNode> getOrEmpty(String path, Map<String, String> params) {
    Http.Response response = execute("GET", path, params, null);
    if (response.status() == 404) {
      return Optional.empty();
    }
    response.requireOk();
    return Optional.of(response.parse(JsonNode.class));
  }

  private Optional<JsonNode> firstEntry(String path, Map<String, String> params) {
    return firstEntry(path, params, null, null);
  }

  /** Lists entries (empty on 404) and picks the one whose {@code field} equals {@code value}. */
  private Optional<JsonNode> firstEntry(String path, Map<String, String> params, String field, String value) {
    Http.Response response = execute("GET", path, params, null);
    if (response.status() == 404) {
      return Optional.empty();
    }
    response.requireOk();
    List<JsonNode> entries = response.parse(Entries.class).entries();
    if (value == null) {
      return entries.stream().findFirst();
    }
    return entries.stream().filter(e -> value.equals(e.path(field).asText(null))).findFirst();
  }

  private Http.Response send(String method, String path, Map<String, String> params, Body body) {
    Http.Response response = execute(method, path, params, body);
    response.requireOk();
    return response;
  }

  /**
   * Sends a request, retrying transient failures. Every memory-server route is safe to retry:
   * writes are idempotent (the server upserts by label or session) and reads are pure.
   */
  private Http.Response execute(String method, String path, Map<String, String> params, Body body) {
    URI uri = Http.withQuery(baseUrl + apiPrefix + path, params);
    Map<String, String> headers = new LinkedHashMap<>(staticHeaders);
    if (dynamicHeaders != null) {
      headers.putAll(dynamicHeaders.get());
    }
    byte[] bytes = null;
    if (body != null) {
      headers.put("Content-Type", "application/json");
      bytes = body.toBytes();
    }
    for (int attempt = 0; ; attempt++) {
      boolean last = attempt >= maxRetries;
      try {
        Http.Response response = Http.send(http, method, uri, headers, bytes, timeout);
        if (last || !RETRYABLE_STATUS.contains(response.status())) {
          return response;
        }
      } catch (UncheckedIOException e) {
        if (last || e.getCause() instanceof InterruptedIOException) {
          throw e;
        }
      }
      backoff(attempt);
    }
  }

  private static void backoff(int attempt) {
    try {
      Thread.sleep(RETRY_BASE_DELAY_MS << attempt);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new UncheckedIOException(new InterruptedIOException("Retry interrupted"));
    }
  }

  // ===========================================================================
  // Helpers
  // ===========================================================================

  /** A JSON request body that skips absent fields but can carry explicit nulls. */
  private static final class Body {
    private final ObjectNode node = Json.mapper().createObjectNode();

    Body put(String key, Object value) {
      if (value != null) {
        node.set(key, Json.mapper().valueToTree(value));
      }
      return this;
    }

    Body putNullable(String key, Object value) {
      node.set(key, value == null ? NullNode.getInstance() : Json.mapper().valueToTree(value));
      return this;
    }

    Body putAll(Body other) {
      node.setAll(other.node);
      return this;
    }

    byte[] toBytes() {
      try {
        return Json.mapper().writeValueAsBytes(node);
      } catch (JsonProcessingException e) {
        throw new IllegalArgumentException("Request is not serializable: " + e.getMessage(), e);
      }
    }
  }

  private record Memories(List<JsonNode> memories) {}

  private record Entries(List<JsonNode> entries) {}

  private record Domains(List<String> domains) {}

  /** Trims a session id, treating blank as absent: the server accepts sessionless requests. */
  private static String normalizeSessionId(String sessionId) {
    return sessionId == null || sessionId.isBlank() ? null : sessionId.trim();
  }

  private static String requireSessionId(String sessionId) {
    String normalized = normalizeSessionId(sessionId);
    if (normalized == null) {
      throw new IllegalArgumentException("sessionId is required for memory server requests");
    }
    return normalized;
  }

  private static String required(String value, String name) {
    if (value == null) {
      throw new IllegalArgumentException(name + " is required for memory server requests");
    }
    return value;
  }

  private static <T> T orDefault(T value, T fallback) {
    return value != null ? value : fallback;
  }

  /** Wire values in alphabetical order, so the request body is deterministic. */
  private static List<String> sortedWireValues(Set<MemorySource> sources) {
    return sources == null
        ? null
        : sources.stream().map(MemorySource::wireValue).sorted(Comparator.naturalOrder()).toList();
  }

  private static String memoryId(JsonNode entry, String kind, String name) {
    JsonNode id = entry.hasNonNull("id") ? entry.get("id") : entry.get("_id");
    if (id == null || id.isNull()) {
      throw new IllegalStateException(kind + " has no id field: " + name);
    }
    return id.asText();
  }

  private String resolveProceduralId(String id, String procedure, String verb) {
    if (id != null) {
      return id;
    }
    if (procedure == null) {
      throw new IllegalArgumentException("id or procedure is required to " + verb + " a procedural memory");
    }
    JsonNode existing =
        getProcedural(GetProceduralRequest.builder().procedure(procedure).build())
            .orElseThrow(() -> new NoSuchElementException("Procedural memory not found: " + procedure));
    return memoryId(existing, "Procedural memory", procedure);
  }
}
