package com.mongodb.agentengine.sdk.memory.http;

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
import com.mongodb.agentengine.sdk.api.v1.memory.RetrieveCustomRequest;
import com.mongodb.agentengine.sdk.api.v1.memory.SearchEpisodicRequest;
import com.mongodb.agentengine.sdk.api.v1.memory.SearchProceduralRequest;
import com.mongodb.agentengine.sdk.api.v1.memory.SearchSemanticRequest;
import com.mongodb.agentengine.sdk.api.v1.memory.SearchTaxonomicRequest;
import com.mongodb.agentengine.sdk.api.v1.memory.WriteTurnRequest;
import com.mongodb.agentengine.sdk.api.v1.memory.WriteTurnResult;
import com.mongodb.agentengine.sdk.clients.HttpStatusException;
import com.mongodb.agentengine.sdk.clients.MemoryClient;
import com.mongodb.agentengine.sdk.clients.MemoryRouteStyle;
import com.mongodb.agentengine.sdk.clients.ResponseFormatException;
import com.mongodb.agentengine.sdk.memory.MemoryApiException;
import com.mongodb.agentengine.sdk.memory.MemoryAuthException;
import com.mongodb.agentengine.sdk.memory.MemoryBadRequestException;
import com.mongodb.agentengine.sdk.memory.MemoryConnectionException;
import com.mongodb.agentengine.sdk.memory.MemoryCrudClient;
import com.mongodb.agentengine.sdk.memory.MemoryNotProvisionedException;
import com.mongodb.agentengine.sdk.memory.MemoryNotSupportedException;
import com.mongodb.agentengine.sdk.memory.MemoryRouteNotFoundException;
import com.mongodb.agentengine.sdk.memory.MemoryRuntime;
import com.mongodb.agentengine.sdk.memory.MemoryServerException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * {@link MemoryRuntime} and {@link MemoryCrudClient} implemented over the core SDK's {@link
 * MemoryClient}.
 *
 * <p>{@code MemoryClient} already owns the HTTP mechanics: URL building, bounded retry, and 404 to
 * empty lookups. This adapter is a thin binding. It leaves tenancy empty, because the backends
 * stamp the trusted org and project from the execution context or the service-account token. It
 * selects the route convention per backend. And it translates the client's failures into this
 * package's typed exceptions.
 *
 * <p>Two clients back each adapter. A retrying one serves the turn write and every read: turns
 * carry an idempotency key and reads are pure. A non-retrying one serves type-specific creates,
 * which have no idempotency key, so a retry after the server committed could duplicate the record.
 */
public final class MemoryClientAdapter implements MemoryRuntime, MemoryCrudClient {

  /** Error codes the backends use to say the memory runtime is not yet reachable. */
  private static final Set<String> NOT_PROVISIONED_CODES =
      Set.of("AGENT_NOT_DEPLOYED", "NO_WORKSPACE_ENDPOINT", "PROJECT_RUNTIME_FAILED");

  private static final int RETRIES = 2;

  private final MemoryClient retrying;
  private final MemoryClient nonRetrying;
  private final boolean projectScoped;

  private MemoryClientAdapter(Builder b) {
    this.projectScoped = b.projectScoped;
    this.retrying = client(b, RETRIES);
    this.nonRetrying = client(b, 0);
  }

  private static MemoryClient client(Builder b, int maxRetries) {
    return MemoryClient.builder(b.baseUrl)
        .apiPrefix(b.apiPrefix)
        .routeStyle(b.routeStyle)
        .staticHeaders(b.headers)
        .dynamicHeaders(b.dynamicHeaders)
        .timeout(b.timeout)
        .httpClient(b.http)
        .maxRetries(maxRetries)
        .build();
  }

  /**
   * Starts an adapter for the backend at {@code baseUrl}.
   *
   * @param apiPrefix the memory path prefix: {@code /api/v1/memory} for the orchestration engine,
   *     or {@code /api/v1/projects/<id>/memory} for the gateway
   * @param routeStyle the route convention the backend speaks
   * @param projectScoped whether the prefix is project-scoped; it only shapes the hint on a 404
   */
  public static Builder builder(
      String baseUrl, String apiPrefix, MemoryRouteStyle routeStyle, boolean projectScoped) {
    return new Builder(baseUrl, apiPrefix, routeStyle, projectScoped);
  }

  /** Builder for {@link MemoryClientAdapter}. */
  public static final class Builder {
    private final String baseUrl;
    private final String apiPrefix;
    private final MemoryRouteStyle routeStyle;
    private final boolean projectScoped;
    private Map<String, String> headers = Map.of();
    private Supplier<Map<String, String>> dynamicHeaders;
    private Duration timeout = Duration.ofSeconds(30);
    private HttpClient http;

    private Builder(String baseUrl, String apiPrefix, MemoryRouteStyle routeStyle, boolean projectScoped) {
      this.baseUrl = baseUrl;
      this.apiPrefix = apiPrefix;
      this.routeStyle = routeStyle;
      this.projectScoped = projectScoped;
    }

    /** Headers sent with every request, such as {@code Authorization}. */
    public Builder headers(Map<String, String> v) { headers = v; return this; }

    /** Headers computed on every request, such as an execution id that varies per call. */
    public Builder dynamicHeaders(Supplier<Map<String, String>> v) { dynamicHeaders = v; return this; }

    /** Per-request timeout. Default: 30 seconds. */
    public Builder timeout(Duration v) { timeout = v; return this; }

    /** The underlying HTTP client, for custom TLS, proxies or tests. */
    public Builder httpClient(HttpClient v) { http = v; return this; }

    public MemoryClientAdapter build() {
      return new MemoryClientAdapter(this);
    }
  }

  /** Does nothing: the underlying {@link HttpClient} holds no resources that need closing. */
  @Override
  public void close() {}

  // ---------------------------------------------------------------------------
  // Error translation
  // ---------------------------------------------------------------------------

  /** Runs a call, translating failures. {@code coreLoop} marks routes whose 404 hints at a route-shape mismatch. */
  private <T> T call(boolean coreLoop, Supplier<T> action) {
    try {
      return action.get();
    } catch (HttpStatusException e) {
      throw mapStatus(e, coreLoop);
    } catch (ResponseFormatException e) {
      // The backend answered, but with a body that breaks its contract: not a transport failure.
      throw new MemoryServerException(e.getMessage(), null, null, null);
    } catch (UncheckedIOException e) {
      throw new MemoryConnectionException(String.valueOf(e.getCause()), null, null, null);
    }
  }

  private MemoryApiException mapStatus(HttpStatusException e, boolean coreLoop) {
    int status = e.status();
    String code = null;
    String message = e.responseText();
    try {
      JsonNode body = Json.mapper().readTree(e.responseText());
      if (body != null && body.isObject()) {
        code = text(body, "code");
        // `detail` is FastAPI's convention, which the memory server follows. Only a string
        // qualifies: validation errors put a list there.
        message = firstNonEmpty(text(body, "error"), text(body, "message"), text(body, "detail"), message);
      }
    } catch (IOException notJson) {
      // A non-JSON body stays as the raw message.
    }
    String text = e.responseText();
    if (status == 401 || status == 403) {
      return new MemoryAuthException(message, status, code, text);
    }
    if (code != null && NOT_PROVISIONED_CODES.contains(code)) {
      return new MemoryNotProvisionedException(message, status, code, text);
    }
    if (status == 404 && coreLoop) {
      return new MemoryRouteNotFoundException(routeNotFoundMessage(), status, code, text);
    }
    if (status >= 400 && status < 500) {
      return new MemoryBadRequestException(message, status, code, text);
    }
    return new MemoryServerException(message, status, code, text);
  }

  private String routeNotFoundMessage() {
    String hint =
        projectScoped
            ? "projectId is set, so the SDK used a project-scoped memory route; if you are "
                + "targeting a local or direct orchestration-engine backend, unset projectId (or "
                + "AGENTIC_MEMORY_PROJECT_ID)"
            : "projectId is empty, so the SDK used a flat memory route; if you are targeting the "
                + "hosted gateway, set projectId (or AGENTIC_MEMORY_PROJECT_ID)";
    return "memory route not found (404). This often means the route shape does not match the "
        + "backend: "
        + hint
        + ". It can also mean baseUrl points at the wrong host.";
  }

  private static String text(JsonNode body, String field) {
    JsonNode value = body.get(field);
    return value != null && value.isTextual() && !value.asText().isEmpty() ? value.asText() : null;
  }

  private static String firstNonEmpty(String... values) {
    for (String value : values) {
      if (value != null && !value.isEmpty()) {
        return value;
      }
    }
    return "";
  }

  // ---------------------------------------------------------------------------
  // MemoryRuntime
  // ---------------------------------------------------------------------------

  @Override
  public WriteTurnResult recordTurn(WriteTurnRequest request) {
    return call(true, () -> retrying.writeTurn(request));
  }

  @Override
  public ContextResponse buildContext(BuildContextRequest request) {
    return call(true, () -> retrying.buildContext(request));
  }

  @Override
  public List<MemoryChunk> searchSemantic(SearchSemanticRequest request) {
    return call(true, () -> retrying.fetchSemanticMemories(request));
  }

  @Override
  public List<MemoryChunk> searchEpisodes(SearchEpisodicRequest request) {
    return call(true, () -> retrying.fetchEpisodicMemories(request));
  }

  @Override
  public List<MemoryChunk> searchTaxonomic(SearchTaxonomicRequest request) {
    return call(true, () -> retrying.fetchTaxonomicMemories(request));
  }

  @Override
  public List<Map<String, Object>> discoverProcedures(SearchProceduralRequest request) {
    return call(true, () -> retrying.discoverProcedures(request));
  }

  // ---------------------------------------------------------------------------
  // MemoryCrudClient
  // ---------------------------------------------------------------------------

  @Override
  public CreateSemanticResult createSemantic(CreateSemanticRequest request) {
    return call(false, () -> nonRetrying.createSemantic(request));
  }

  @Override
  public Optional<JsonNode> getSemantic(GetSemanticRequest request) {
    return call(false, () -> retrying.getSemantic(request));
  }

  @Override
  public CreateEpisodicResult createEpisodic(CreateEpisodicRequest request) {
    return call(false, () -> nonRetrying.createEpisodic(request));
  }

  @Override
  public List<JsonNode> listEpisodic(ListEpisodicRequest request) {
    return call(false, () -> retrying.listEpisodic(request));
  }

  @Override
  public CreateTaxonomicResult createTaxonomic(CreateTaxonomicRequest request) {
    return call(false, () -> nonRetrying.createTaxonomic(request));
  }

  @Override
  public Optional<JsonNode> getTaxonomic(GetTaxonomicRequest request) {
    return call(false, () -> retrying.getTaxonomic(request));
  }

  @Override
  public List<String> getDistinctDomains(String visibility) {
    return call(false, () -> retrying.getDistinctDomains(visibility));
  }

  @Override
  public CreateProceduralResult createProcedural(CreateProceduralRequest request) {
    return call(false, () -> nonRetrying.createProcedural(request));
  }

  @Override
  public Optional<JsonNode> getProcedural(GetProceduralRequest request) {
    return call(false, () -> retrying.getProcedural(request));
  }

  @Override
  public CustomMemorySaveResult createCustom(CreateCustomRequest request) {
    return callCustom(() -> nonRetrying.createCustom(request));
  }

  @Override
  public CustomMemoryRetrieveResult retrieveCustom(RetrieveCustomRequest request) {
    return callCustom(() -> retrying.retrieveCustom(request));
  }

  /**
   * Translates failures of the custom-type routes. A bare 404 or 405 means the platform predates
   * custom types, and a gateway with the feature off rejects with a 400 naming it. The server's
   * unknown-type 404 names the type in a structured body and passes through untouched.
   */
  private <T> T callCustom(Supplier<T> action) {
    try {
      return call(false, action);
    } catch (MemoryBadRequestException e) {
      String message = String.valueOf(e.getMessage());
      Integer status = e.status();
      if ((Integer.valueOf(404).equals(status) || Integer.valueOf(405).equals(status))
          && !message.contains("unknown custom memory type")) {
        throw new MemoryNotSupportedException(
            "this platform version does not support custom memory types; save/retrieve requires a "
                + "platform release with the custom memory type operations enabled",
            e);
      }
      // The gateway's flag-off rejection reuses the generic INVALID_REQUEST code, so this message
      // substring is the only stable discriminator for that 400.
      if (Integer.valueOf(400).equals(status) && message.contains("custom_memory_types is not enabled")) {
        throw new MemoryNotSupportedException(
            "custom memory types are disabled on this deployment; save/retrieve requires the "
                + "custom_memory_types feature to be enabled",
            e);
      }
      throw e;
    }
  }
}
