package com.mongodb.agentengine.runner.models;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mongodb.agentengine.sdk.Json;
import java.io.IOException;
import java.util.Objects;

/**
 * A request from the orchestration engine to run an LLM call on the tool server.
 *
 * @param stepNumber the call's step, which a per-call interrupt addresses
 */
public record LlmPodInvokeRequest(String executionId, InvokeLlmArguments arguments, Integer stepNumber) {

  public LlmPodInvokeRequest {
    Objects.requireNonNull(executionId, "execution_id");
    Objects.requireNonNull(arguments, "arguments");
  }

  /**
   * Parses a request in either shape the OE sends: the arguments under {@code arguments}, or flat,
   * with the arguments beside {@code execution_id}.
   *
   * @throws IOException if the body is not a valid request
   */
  public static LlmPodInvokeRequest parse(byte[] body) throws IOException {
    JsonNode root = Json.mapper().readTree(body);
    if (root == null || !root.isObject()) {
      throw new IOException("Request body must be a JSON object");
    }
    if (root.has("arguments") || !root.has("execution_id")) {
      return Json.mapper().treeToValue(root, LlmPodInvokeRequest.class);
    }
    ObjectNode flat = ((ObjectNode) root).deepCopy();
    ObjectNode request = Json.mapper().createObjectNode();
    request.set("execution_id", flat.remove("execution_id"));
    for (String routing : new String[] {"platform_trace_id", "step_number"}) {
      if (flat.has(routing)) {
        request.set(routing, flat.remove(routing));
      }
    }
    request.set("arguments", flat);
    return Json.mapper().treeToValue(request, LlmPodInvokeRequest.class);
  }
}
