package com.mongodb.agentengine.runner.models;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The orchestration engine's answer to a {@link ToolExecuteRequest}.
 *
 * @param proceed whether the call may run
 * @param routeTo {@code "callback"} when this runtime should run a local tool; a stream URL when the
 *     OE relays an LLM stream; otherwise null
 * @param reason why the call was refused, when {@code proceed} is false
 * @param status the final status when the OE ran the call itself: {@code success}, {@code error},
 *     {@code interrupted} or {@code suspend}
 * @param result the OE-run call's result, or the substitute text of a guardrail block
 * @param latestStepNumber the highest step the OE has seen, so step numbers stay unique
 */
public record ToolExecuteResponse(
    boolean proceed,
    JsonNode cachedResult,
    String routeTo,
    String reason,
    String status,
    JsonNode result,
    String error,
    String errorCode,
    boolean retryable,
    Integer latestStepNumber,
    boolean fromCache,
    Double durationMs,
    String podName) {}
