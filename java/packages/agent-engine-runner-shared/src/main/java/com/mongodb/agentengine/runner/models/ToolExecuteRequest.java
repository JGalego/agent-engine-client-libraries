package com.mongodb.agentengine.runner.models;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

/**
 * A request to the orchestration engine to approve, and possibly run, a tool or LLM call.
 *
 * @param stepNumber the call's position in the execution
 * @param toolCallId the model's id for the call, joining the result to its call
 * @param isLocal whether the OE should hand an approved call back to this runtime to run
 * @param redactFields top-level argument names to keep out of execution logs
 * @param customHeaders caller-provided headers carried for this request only
 */
public record ToolExecuteRequest(
    String executionId,
    String toolName,
    JsonNode arguments,
    int stepNumber,
    String toolCallId,
    String kind,
    boolean isLocal,
    List<String> redactFields,
    String providerType,
    List<String> scopes,
    Map<String, Object> metadata,
    Map<String, String> customHeaders) {}
