package com.mongodb.agentengine.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/**
 * Event streamed back to the caller during an execution.
 *
 * @param data the event body, an opaque JSON value
 * @param event an optional event name
 */
public record StreamEvent(JsonNode data, String event) {

  public StreamEvent {
    Objects.requireNonNull(data, "data");
    // An SSE `event:` line ends at CR or LF, so a name carrying either could forge extra frames
    // in any server-sent-event serializer. NUL can truncate the name in C-based parsers and log
    // sinks. Reject all three here, whichever transport later renders the name.
    if (event != null && (event.indexOf('\r') >= 0 || event.indexOf('\n') >= 0 || event.indexOf('\0') >= 0)) {
      throw new IllegalArgumentException("Stream event name must not contain CR, LF, or NUL");
    }
  }

  /** Creates an unnamed event. */
  public StreamEvent(JsonNode data) {
    this(data, null);
  }
}
