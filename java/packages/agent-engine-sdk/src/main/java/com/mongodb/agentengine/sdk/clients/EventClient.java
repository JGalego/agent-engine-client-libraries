package com.mongodb.agentengine.sdk.clients;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mongodb.agentengine.sdk.BranchRef;
import com.mongodb.agentengine.sdk.Event;
import com.mongodb.agentengine.sdk.Json;
import com.mongodb.agentengine.sdk.api.EventResponse;
import com.mongodb.agentengine.sdk.api.EventsResponse;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * HTTP client for the platform event API.
 *
 * <p>Failed requests throw {@link HttpStatusException}; network failures throw {@link
 * java.io.UncheckedIOException}.
 */
public final class EventClient {

  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

  private final String oeUrl;
  private final Duration timeout;
  private final HttpClient http;

  /** Creates a client for the orchestration engine at {@code oeUrl} with a 30 second timeout. */
  public EventClient(String oeUrl) {
    this(oeUrl, DEFAULT_TIMEOUT);
  }

  /** Creates a client whose requests give up after {@code timeout}. */
  public EventClient(String oeUrl, Duration timeout) {
    this(oeUrl, timeout, Http.newClient(timeout));
  }

  /** Creates a client over a caller-configured {@link HttpClient}, for custom TLS or proxies. */
  public EventClient(String oeUrl, Duration timeout, HttpClient http) {
    this.oeUrl = Http.trimTrailingSlash(oeUrl);
    this.timeout = timeout;
    this.http = http;
  }

  /** Appends an event to a session. */
  public Event appendEvent(String sessionId, String actorId, JsonNode payload) {
    return appendEvent(sessionId, actorId, payload, null, null, null);
  }

  /**
   * Appends an event to a session.
   *
   * @param parentEventId the event this one follows; may be null
   * @param metadata string metadata to filter on later; may be null
   * @param branch the branch to append to; may be null for the main line
   */
  public Event appendEvent(
      String sessionId,
      String actorId,
      JsonNode payload,
      String parentEventId,
      Map<String, String> metadata,
      BranchRef branch) {
    ObjectNode body = Json.mapper().createObjectNode();
    body.put("actor_id", actorId);
    body.set("payload", payload);
    if (parentEventId != null) {
      body.put("parent_event_id", parentEventId);
    }
    if (metadata != null) {
      body.set("metadata", Json.mapper().valueToTree(metadata));
    }
    if (branch != null) {
      body.set("branch", Json.mapper().valueToTree(branch));
    }
    byte[] bytes;
    try {
      bytes = Json.mapper().writeValueAsBytes(body);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Event payload is not serializable", e);
    }

    Http.Response response =
        Http.send(
            http,
            "POST",
            URI.create(sessionEventsUrl(sessionId)),
            Map.of("Content-Type", "application/json"),
            bytes,
            timeout);
    response.requireOk();
    return response.parse(EventResponse.class).event();
  }

  /** Lists all events of a session. */
  public List<Event> listEvents(String sessionId) {
    return listEvents(sessionId, null, null);
  }

  /**
   * Lists events of a session.
   *
   * @param branch only events on this branch; may be null
   * @param metadataFilters only events whose metadata matches every entry; may be null
   */
  public List<Event> listEvents(
      String sessionId, String branch, Map<String, String> metadataFilters) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("branch", branch);
    if (metadataFilters != null) {
      metadataFilters.forEach((key, value) -> params.put("metadata." + key, value));
    }
    Http.Response response =
        Http.send(http, "GET", Http.withQuery(sessionEventsUrl(sessionId), params), Map.of(), null, timeout);
    response.requireOk();
    return response.parse(EventsResponse.class).events();
  }

  /** Fetches one event, or returns empty when it does not exist. */
  public Optional<Event> getEvent(String sessionId, String eventId) {
    Http.Response response =
        Http.send(
            http,
            "GET",
            URI.create(sessionEventsUrl(sessionId) + "/" + PathSegments.encode(eventId)),
            Map.of(),
            null,
            timeout);
    if (response.status() == 404) {
      return Optional.empty();
    }
    response.requireOk();
    return Optional.of(response.parse(EventResponse.class).event());
  }

  private String sessionEventsUrl(String sessionId) {
    return oeUrl + "/v1/sessions/" + PathSegments.encode(sessionId) + "/events";
  }
}
