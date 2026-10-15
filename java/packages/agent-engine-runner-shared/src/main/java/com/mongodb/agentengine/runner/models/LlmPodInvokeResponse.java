package com.mongodb.agentengine.runner.models;

import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.agentengine.sdk.LLMTokenUsage;

/**
 * The tool server's answer to a non-streaming LLM call.
 *
 * @param status {@code success} or {@code error}
 * @param result the response in the SDK's wire format
 * @param errorCode a machine-readable failure classification, when errored
 */
public record LlmPodInvokeResponse(
    String status, JsonNode result, String error, String errorCode, String podName, double durationMs, LLMTokenUsage usage) {}
