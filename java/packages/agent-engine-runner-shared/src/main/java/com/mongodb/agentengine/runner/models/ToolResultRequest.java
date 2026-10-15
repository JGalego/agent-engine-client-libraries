package com.mongodb.agentengine.runner.models;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/**
 * A report to the orchestration engine of how a locally run tool finished.
 *
 * @param status {@code success} or {@code error}
 */
public record ToolResultRequest(
    String executionId,
    int stepNumber,
    String toolName,
    String toolCallId,
    String status,
    JsonNode result,
    String error,
    double durationMs,
    String podName,
    Map<String, Object> metadata) {}
