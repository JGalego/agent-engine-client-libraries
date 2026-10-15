package com.mongodb.agentengine.runner.models;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/**
 * The runtime's report to the orchestration engine that an execution finished.
 *
 * @param executionId the execution
 * @param status {@code COMPLETED}, {@code SUSPENDED} or {@code ERROR}
 * @param suspendGeneration echoed from the dispatch that started the work, when it carried one
 * @param result the final result, when completed
 * @param error the failure message, when errored
 * @param suspendReason why the execution suspended, when suspended
 * @param suspendContext what a reviewer needs to decide, when suspended
 * @param metadata framework-owned state the platform hands back on resume, when suspended
 */
public record ExecutorCallback(
    String executionId,
    String status,
    Integer suspendGeneration,
    JsonNode result,
    String error,
    String suspendReason,
    Map<String, Object> suspendContext,
    Map<String, Object> metadata) {

  public static ExecutorCallback completed(String executionId, Integer suspendGeneration, JsonNode result) {
    return new ExecutorCallback(executionId, "COMPLETED", suspendGeneration, result, null, null, null, null);
  }

  public static ExecutorCallback failed(String executionId, Integer suspendGeneration, String error) {
    return new ExecutorCallback(executionId, "ERROR", suspendGeneration, null, error, null, null, null);
  }

  public static ExecutorCallback suspended(String executionId, Integer suspendGeneration, String reason, Map<String, Object> context, Map<String, Object> metadata) {
    return new ExecutorCallback(executionId, "SUSPENDED", suspendGeneration, null, null, reason, context, metadata.isEmpty() ? null : metadata);
  }
}
