package com.mongodb.agentengine.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Platform event: the storage envelope around an opaque payload.
 *
 * @param eventId the event id
 * @param sessionId the session the event belongs to
 * @param parentEventId the preceding event, if any
 * @param actorId who produced the event
 * @param payload the event body
 * @param metadata string metadata, filterable when listing events
 * @param branch the branch the event is on, if not the main line
 * @param timestamp when the event was stored
 */
public record Event(
    String eventId,
    String sessionId,
    String parentEventId,
    String actorId,
    JsonNode payload,
    Map<String, String> metadata,
    BranchRef branch,
    @JsonDeserialize(using = Json.LenientInstantDeserializer.class) Instant timestamp) {

  public Event {
    Objects.requireNonNull(eventId, "eventId");
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(actorId, "actorId");
    Objects.requireNonNull(timestamp, "timestamp");
  }
}
