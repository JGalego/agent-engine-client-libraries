package com.mongodb.agentengine.runner.models;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/**
 * The tool server's answer to a {@link ToolPodExecuteRequest}.
 *
 * @param status {@code success} or {@code error}
 * @param podName the host that ran the tool
 * @param oobSuspendSupported whether this runner reports suspension through {@code status} instead
 *     of in the result body
 */
public record ToolPodExecuteResponse(
    String status,
    JsonNode result,
    String error,
    String podName,
    String kind,
    Map<String, Object> metadata,
    boolean oobSuspendSupported) {}
