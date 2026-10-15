package com.mongodb.agentengine.runner;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.agentengine.sdk.Json;
import com.mongodb.agentengine.sdk.SuspendException;
import java.util.LinkedHashMap;
import java.util.Map;

/** The wire form of a suspension: a JSON string carrying the payload and the {@code __suspend__} flag. */
public final class SuspendMarker {

  private SuspendMarker() {}

  /** Encodes a suspension as the string a tool server returns with status {@code suspend}. */
  public static String encode(SuspendException suspension) {
    Map<String, Object> marker = new LinkedHashMap<>();
    marker.put("suspend_reason", suspension.reason());
    marker.put("suspend_context", suspension.context());
    marker.put("__suspend__", true);
    return Json.mapper().valueToTree(marker).toString();
  }

  /** Decodes the result of a call the OE reported as suspended. */
  public static SuspendException decode(JsonNode result) {
    try {
      JsonNode marker = result != null && result.isTextual() ? Json.mapper().readTree(result.asText()) : result;
      if (marker == null || !marker.hasNonNull("suspend_reason")) {
        throw new ToolExecutionException("The OE reported a suspension without a reason");
      }
      Map<String, Object> context =
          marker.hasNonNull("suspend_context") ? Json.mapper().convertValue(marker.get("suspend_context"), new TypeReference<>() {}) : Map.of();
      return new SuspendException(marker.get("suspend_reason").asText(), context);
    } catch (JsonProcessingException e) {
      throw new ToolExecutionException("The OE reported a suspension with an unreadable payload: " + e.getMessage());
    }
  }
}
