package com.mongodb.agentengine.sdk.memory;

import com.fasterxml.jackson.databind.JsonNode;
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
import com.mongodb.agentengine.sdk.api.v1.memory.WriteTurnRequest;
import com.mongodb.agentengine.sdk.api.v1.memory.WriteTurnResult;
import com.mongodb.agentengine.sdk.clients.MemoryRouteStyle;
import com.mongodb.agentengine.sdk.clients.PathSegments;
import com.mongodb.agentengine.sdk.memory.http.MemoryClientAdapter;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Transport-free public memory facade.
 *
 * <p>It delegates conversation-loop operations to a {@link MemoryRuntime} and type-specific saves,
 * gets and lists to a {@link MemoryCrudClient}. Identity is resolved per call, in this order: the
 * request's own {@code userId}, {@code agentId} and {@code sessionId}; then the context set with
 * {@link #bind}; then the runtime's ambient context. Tenancy never appears in a signature.
 *
 * <p>All methods block. A facade is immutable and safe to share between threads.
 *
 * <pre>{@code
 * try (Memory memory = Memory.builder().serviceAccountToken(token).projectId("my-project").build()) {
 *   memory.saveSemantic(CreateSemanticRequest.builder("favorite_color", "teal").userId("u1").build());
 *   List<MemoryChunk> hits = memory.searchSemantic(SearchSemanticRequest.builder("color").userId("u1").build());
 * }
 * }</pre>
 */
public final class Memory implements AutoCloseable {

  static final String DEFAULT_GATEWAY_URL = "https://agentengine.mongodb.com";
  static final String SERVICE_ACCOUNT_TOKEN_ENV_VAR = "AGENTIC_MEMORY_SERVICE_ACCOUNT_TOKEN";
  static final String BASE_URL_ENV_VAR = "AGENTIC_MEMORY_BASE_URL";
  static final String PROJECT_ID_ENV_VAR = "AGENTIC_MEMORY_PROJECT_ID";

  /** Sources searched when none are given: the two ranked similarity sources. */
  private static final Set<SearchSource> DEFAULT_SEARCH_SOURCES =
      EnumSet.of(SearchSource.SEMANTIC, SearchSource.EPISODIC);

  private final MemoryRuntime runtime;
  private final MemoryCrudClient client;
  private final MemoryRequestContext bound;
  private final boolean ownsTransport;

  private Memory(MemoryRuntime runtime, MemoryCrudClient client, MemoryRequestContext bound, boolean ownsTransport) {
    this.runtime = runtime;
    this.client = client;
    this.bound = bound;
    this.ownsTransport = ownsTransport;
  }

  /** Starts a facade that talks to the memory backend over HTTP. */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Wraps a custom runtime. Type-specific save, get and list operations then throw {@link
   * MemoryNotSupportedException}; use {@link #of(MemoryRuntime, MemoryCrudClient)} to provide them.
   */
  public static Memory of(MemoryRuntime runtime) {
    return of(runtime, null);
  }

  /** Wraps a custom runtime and CRUD client. The facade does not close them. */
  public static Memory of(MemoryRuntime runtime, MemoryCrudClient client) {
    return new Memory(runtime, client, null, false);
  }

  /**
   * Builder for an HTTP-direct facade, configured from arguments and {@code AGENTIC_MEMORY_*}
   * environment variables. An argument wins over its variable.
   *
   * <p>The service-account token is optional only when a base URL is set: with a token and no base
   * URL the facade targets the hosted gateway. A project id selects project-scoped gateway routes;
   * without one it uses the flat orchestration-engine routes.
   */
  public static final class Builder {
    private String serviceAccountToken;
    private String baseUrl;
    private String projectId;
    private Duration timeout = Duration.ofSeconds(30);
    private HttpClient http;
    private Function<String, String> environment = System::getenv;

    private Builder() {}

    /** A service-account access token. Env: {@code AGENTIC_MEMORY_SERVICE_ACCOUNT_TOKEN}. */
    public Builder serviceAccountToken(String v) { serviceAccountToken = v; return this; }

    /** The backend address. Env: {@code AGENTIC_MEMORY_BASE_URL}. */
    public Builder baseUrl(String v) { baseUrl = v; return this; }

    /** The project, for project-scoped routes. Env: {@code AGENTIC_MEMORY_PROJECT_ID}. */
    public Builder projectId(String v) { projectId = v; return this; }

    /** Per-request timeout. Default: 30 seconds. */
    public Builder timeout(Duration v) { timeout = v; return this; }

    /** The underlying HTTP client, for custom TLS, proxies or tests. */
    public Builder httpClient(HttpClient v) { http = v; return this; }

    /** Replaces the environment lookup, for tests. */
    Builder environment(Function<String, String> v) { environment = v; return this; }

    /**
     * Builds the facade.
     *
     * @throws IllegalArgumentException if a value is blank, or neither a base URL nor a token is
     *     available to locate the backend
     */
    public Memory build() {
      String token = serviceAccountToken != null ? serviceAccountToken : envOrNull(SERVICE_ACCOUNT_TOKEN_ENV_VAR);
      if (token != null && token.isBlank()) {
        throw new IllegalArgumentException("serviceAccountToken must be a non-empty string");
      }
      // A blank project id is unset: the discriminator is presence, and an empty path segment
      // would misroute. Surrounding whitespace is stripped.
      String project = projectId != null ? projectId : environment.apply(PROJECT_ID_ENV_VAR);
      project = project == null || project.isBlank() ? null : project.trim();

      String url = resolveUrl(token);
      String apiPrefix =
          project != null ? "/api/v1/projects/" + PathSegments.encode(project) + "/memory" : "/api/v1/memory";
      MemoryClientAdapter adapter =
          MemoryClientAdapter.builder(url, apiPrefix, MemoryRouteStyle.ALIASED, project != null)
              .headers(token != null ? Map.of("Authorization", "Bearer " + token) : Map.of())
              .timeout(timeout)
              .httpClient(http)
              .build();
      return new Memory(adapter, adapter, null, true);
    }

    private String resolveUrl(String token) {
      if (baseUrl != null && baseUrl.isBlank()) {
        throw new IllegalArgumentException("baseUrl must be a non-empty string");
      }
      String resolved = baseUrl != null ? baseUrl : environment.apply(BASE_URL_ENV_VAR);
      if (resolved != null && !resolved.isBlank()) {
        return resolved.trim();
      }
      if (token != null) {
        return DEFAULT_GATEWAY_URL;
      }
      throw new IllegalArgumentException(
          "Memory requires baseUrl, the " + BASE_URL_ENV_VAR + " environment variable, or a "
              + "service-account token, which falls back to the hosted default " + DEFAULT_GATEWAY_URL);
    }

    /**
     * Returns the variable's value, or null when unset or blank. An empty-exported placeholder
     * (common in CI, Helm and dotenv files) is not a credential, so it must not authenticate.
     */
    private String envOrNull(String name) {
      String value = environment.apply(name);
      return value != null && !value.isBlank() ? value : null;
    }
  }

  /**
   * Returns a facade scoped to {@code context} without changing this one. The context fills the
   * identity fields a request leaves blank, ahead of the runtime's ambient context. A bound facade
   * shares this one's transport but never closes it.
   */
  public Memory bind(MemoryRequestContext context) {
    return new Memory(runtime, client, context, false);
  }

  /** Releases the transport when this facade built it. Facades from {@link #of} and {@link #bind} do not. */
  @Override
  public void close() {
    if (ownsTransport) {
      runtime.close();
      if (client != null && client != runtime) {
        client.close();
      }
    }
  }

  private MemoryCrudClient requireClient() {
    if (client == null) {
      throw new MemoryNotSupportedException(
          "type-specific save/get/list operations require a CRUD client; provide one with "
              + "Memory.of(runtime, client) when injecting a custom runtime, or use recordTurn, "
              + "buildContext or search");
    }
    return client;
  }

  private MemoryRequestContext runtimeContext() {
    // Read fresh on every call so a long-lived facade picks up context that varies per request.
    return runtime instanceof AmbientIdentityRuntime ambient ? ambient.requestContext() : null;
  }

  private MemoryRequestContext resolve(
      String userId,
      String agentId,
      String sessionId,
      Set<IdentityField> required,
      String visibility,
      boolean suppressInheritedSessionId) {
    MemoryRequestContext ambient = runtimeContext();
    // Security: an ambient identity is the trusted principal resolved from the execution context.
    // A caller- or bind-supplied userId that disagrees with it must never take effect; otherwise a
    // tool that exposes userId as an LLM-controllable argument could read or write another user's
    // memory through the same proxied route. Fail closed rather than silently honoring the
    // override. An equal or absent userId is fine.
    String ambientUserId = ambient == null ? null : Identity.normalize(ambient.userId());
    if (ambientUserId != null) {
      String requested = Identity.normalize(userId);
      if (requested == null && bound != null) {
        requested = Identity.normalize(bound.userId());
      }
      if (requested != null && !requested.equals(ambientUserId)) {
        throw new MemoryIdentityException(
            "userId does not match the ambient app-bound identity: in app-bound mode the user is "
                + "resolved from the execution context and cannot be overridden by a "
                + "caller-supplied userId");
      }
    }
    return Identity.resolve(
        new MemoryRequestContext(userId, agentId, sessionId),
        bound,
        ambient,
        required,
        visibility != null && !visibility.equals("private"),
        suppressInheritedSessionId);
  }

  private static final Set<IdentityField> USER = EnumSet.of(IdentityField.USER_ID);
  private static final Set<IdentityField> USER_AND_SESSION =
      EnumSet.of(IdentityField.USER_ID, IdentityField.SESSION_ID);
  private static final Set<IdentityField> NONE = EnumSet.noneOf(IdentityField.class);

  // ==========================================================================
  // Conversation turns
  // ==========================================================================

  /**
   * Records one conversation turn. An idempotency key is generated when the request has none, so
   * transport retries are safe; deduplicating across separate calls needs a caller-supplied key.
   */
  public WriteTurnResult recordTurn(WriteTurnRequest request) {
    MemoryRequestContext id = resolve(request.userId(), null, request.sessionId(), NONE, null, false);
    return runtime.recordTurn(
        request.toBuilder()
            .userId(id.userId())
            .sessionId(id.sessionId())
            .idempotencyKey(request.idempotencyKey() != null ? request.idempotencyKey() : UUID.randomUUID().toString())
            .build());
  }

  // ==========================================================================
  // Semantic memory
  // ==========================================================================

  /** Saves a fact. Unlike the raw client, it updates an existing fact with the same label unless {@code upsert} is false. */
  public CreateSemanticResult saveSemantic(CreateSemanticRequest request) {
    MemoryRequestContext id = resolve(request.userId(), request.agentId(), null, USER, null, false);
    return requireClient()
        .createSemantic(
            request.toBuilder()
                .userId(id.userId())
                .agentId(id.agentId())
                .upsert(request.upsert() != null ? request.upsert() : Boolean.TRUE)
                .build());
  }

  public List<MemoryChunk> searchSemantic(SearchSemanticRequest request) {
    MemoryRequestContext id = resolve(request.userId(), null, null, NONE, request.visibility(), false);
    return runtime.searchSemantic(request.toBuilder().userId(id.userId()).build());
  }

  public Optional<JsonNode> getSemantic(GetSemanticRequest request) {
    MemoryRequestContext id = resolve(request.userId(), null, null, NONE, request.visibility(), false);
    return requireClient().getSemantic(request.toBuilder().userId(id.userId()).build());
  }

  // ==========================================================================
  // Episodic memory
  // ==========================================================================

  public CreateEpisodicResult saveEpisode(CreateEpisodicRequest request) {
    MemoryRequestContext id =
        resolve(request.userId(), request.agentId(), request.sessionId(), USER_AND_SESSION, null, false);
    return requireClient()
        .createEpisodic(
            request.toBuilder().userId(id.userId()).agentId(id.agentId()).sessionId(id.sessionId()).build());
  }

  public List<MemoryChunk> searchEpisodes(SearchEpisodicRequest request) {
    MemoryRequestContext id = resolve(request.userId(), null, request.sessionId(), NONE, request.visibility(), true);
    return runtime.searchEpisodes(request.toBuilder().userId(id.userId()).sessionId(id.sessionId()).build());
  }

  public List<JsonNode> listEpisodes(ListEpisodicRequest request) {
    MemoryRequestContext id = resolve(request.userId(), null, request.sessionId(), NONE, request.visibility(), true);
    return requireClient().listEpisodic(request.toBuilder().userId(id.userId()).sessionId(id.sessionId()).build());
  }

  /** Lists episodic memories with no filters beyond the resolved identity. */
  public List<JsonNode> listEpisodes() {
    return listEpisodes(ListEpisodicRequest.builder().build());
  }

  // ==========================================================================
  // Taxonomic memory
  // ==========================================================================

  public CreateTaxonomicResult saveTaxonomic(CreateTaxonomicRequest request) {
    MemoryRequestContext id = resolve(request.userId(), null, null, USER, null, false);
    return requireClient().createTaxonomic(request.toBuilder().userId(id.userId()).build());
  }

  /** Searches the knowledge base. It is org-scoped, so no user filter applies. */
  public List<MemoryChunk> searchTaxonomic(SearchTaxonomicRequest request) {
    return runtime.searchTaxonomic(request);
  }

  public Optional<JsonNode> getTaxonomicTerm(GetTaxonomicRequest request) {
    MemoryRequestContext id = resolve(request.userId(), null, null, NONE, request.visibility(), false);
    return requireClient().getTaxonomic(request.toBuilder().userId(id.userId()).build());
  }

  /** Lists the knowledge-base domains visible with the given visibility, or all when null. */
  public List<String> listDomains(String visibility) {
    return requireClient().getDistinctDomains(visibility);
  }

  public List<String> listDomains() {
    return listDomains(null);
  }

  // ==========================================================================
  // Procedural memory
  // ==========================================================================

  /** Finds procedures by similarity. Each result holds the name, content and score, plus the stored metadata. */
  public List<Map<String, Object>> discoverProcedures(SearchProceduralRequest request) {
    MemoryRequestContext id = resolve(request.userId(), null, null, NONE, request.visibility(), false);
    return runtime.discoverProcedures(request.toBuilder().userId(id.userId()).build());
  }

  public Optional<JsonNode> getProcedure(GetProceduralRequest request) {
    MemoryRequestContext id = resolve(request.userId(), null, null, NONE, request.visibility(), false);
    return requireClient().getProcedural(request.toBuilder().userId(id.userId()).build());
  }

  /** Saves a procedure. The HTTP backends always create; there is no update-in-place. */
  public CreateProceduralResult saveProcedure(CreateProceduralRequest request) {
    MemoryRequestContext id = resolve(request.userId(), request.agentId(), null, USER, null, false);
    return requireClient()
        .createProcedural(request.toBuilder().userId(id.userId()).agentId(id.agentId()).build());
  }

  // ==========================================================================
  // Custom memory types
  // ==========================================================================

  /**
   * Saves a memory of a declared custom type.
   *
   * <p>The platform stamps identity (org, project, user) and enforces the type's declared tag
   * schema. This method only checks tag syntax client-side, so mistakes fail fast with messages
   * matching the server's. Built-in types are rejected; use their dedicated methods.
   *
   * @throws MemoryClientException if the type name or a tag is malformed
   */
  public CustomMemorySaveResult save(CreateCustomRequest request) {
    validateCustom(request.memoryType(), request.tags());
    return requireClient().createCustom(request);
  }

  /**
   * Retrieves memories of a declared custom type by semantic query. Tag filters are exact-match
   * equality on declared tag keys, one type per call. Result ordering is not contractual.
   *
   * @throws MemoryClientException if the type name or a tag is malformed
   */
  public CustomMemoryRetrieveResult retrieve(RetrieveCustomRequest request) {
    validateCustom(request.memoryType(), request.tags());
    return requireClient().retrieveCustom(request);
  }

  private static void validateCustom(String memoryType, Map<String, Object> tags) {
    TagSyntax.validateMemoryType(memoryType);
    if (tags != null) {
      TagSyntax.validateTagSyntax(tags);
    }
  }

  // ==========================================================================
  // Context building
  // ==========================================================================

  /** Builds a unified memory context across memory types. */
  public ContextResponse buildContext(BuildContextRequest request) {
    MemoryRequestContext id = resolve(request.userId(), null, request.sessionId(), NONE, request.visibility(), false);
    return runtime.buildContext(request.toBuilder().userId(id.userId()).sessionId(id.sessionId()).build());
  }

  // ==========================================================================
  // Unified search
  // ==========================================================================

  /** Searches one or more sources and returns a single list ranked by similarity, unscored chunks last. */
  public List<MemoryChunk> search(SearchRequest request) {
    int topK = request.topK() != null ? request.topK() : 10;
    // The session feeds only the episodic leg. Suppressing inheritance on this one shared resolve
    // is safe for every leg and stops a bound or ambient session from filtering episodic; an
    // explicit session still filters.
    MemoryRequestContext id =
        resolve(request.userId(), null, request.sessionId(), NONE, request.visibility(), true);
    Set<SearchSource> sources = request.sources() != null ? request.sources() : DEFAULT_SEARCH_SOURCES;

    List<MemoryChunk> chunks = new ArrayList<>();
    for (SearchSource source : new LinkedHashSet<>(sources)) {
      switch (source) {
        case SEMANTIC ->
            chunks.addAll(
                runtime.searchSemantic(
                    SearchSemanticRequest.builder(request.query())
                        .userId(id.userId())
                        .visibility(request.visibility())
                        .topK(topK)
                        .build()));
        case EPISODIC ->
            chunks.addAll(
                runtime.searchEpisodes(
                    SearchEpisodicRequest.builder(request.query())
                        .userId(id.userId())
                        .sessionId(id.sessionId())
                        .visibility(request.visibility())
                        .topK(topK)
                        .build()));
        case TAXONOMIC ->
            chunks.addAll(
                runtime.searchTaxonomic(
                    SearchTaxonomicRequest.builder(request.query())
                        .domain(request.domain())
                        .visibility(request.visibility())
                        .topK(topK)
                        .build()));
        case PROCEDURAL ->
            runtime
                .discoverProcedures(
                    SearchProceduralRequest.builder(request.query())
                        .userId(id.userId())
                        .visibility(request.visibility())
                        .tags(request.tags())
                        .topK(topK)
                        .similarityThreshold(request.similarityThreshold() != null ? request.similarityThreshold() : 0.0)
                        .metadataFilter(request.metadataFilter())
                        .build())
                .forEach(procedure -> chunks.add(procedureToChunk(procedure)));
      }
    }
    // Stable sort: highest score first, unscored chunks last.
    chunks.sort(
        Comparator.comparing(MemoryChunk::similarityScore, Comparator.nullsLast(Comparator.reverseOrder())));
    return chunks.size() > topK ? new ArrayList<>(chunks.subList(0, topK)) : chunks;
  }

  /** Adapts a discovered procedure to a chunk, so it ranks alongside the other sources. */
  private static MemoryChunk procedureToChunk(Map<String, Object> procedure) {
    Object id = firstPresent(procedure, "id", "_id");
    Object content = firstPresent(procedure, "content", "description", "procedure");
    Object score = firstPresent(procedure, "score", "similarity_score");
    return new MemoryChunk(
        id == null ? "" : String.valueOf(id),
        content == null ? "" : String.valueOf(content),
        MemorySource.PROCEDURAL,
        toInstant(firstPresent(procedure, "timestamp", "created_at", "updated_at")),
        null,
        score instanceof Number n ? n.doubleValue() : null,
        procedure);
  }

  private static Object firstPresent(Map<String, Object> map, String... keys) {
    for (String key : keys) {
      if (map.get(key) != null) {
        return map.get(key);
      }
    }
    return null;
  }

  /** Reads an ISO string or epoch milliseconds. A missing value is the epoch, not now, so it never looks fresh. */
  private static Instant toInstant(Object value) {
    if (value == null) {
      return Instant.EPOCH;
    }
    if (value instanceof Number n) {
      return Instant.ofEpochMilli(n.longValue());
    }
    return Json.parseInstant(String.valueOf(value));
  }
}
