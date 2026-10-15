package com.mongodb.agentengine.runner.models;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The runtime's report to the orchestration engine that an execution finished.
 *
 * @param executionId the execution
 * @param status {@code COMPLETED}, {@code SUSPENDED} or {@code ERROR}
 * @param suspendGeneration echoed from the dispatch that started the work, when it carried one
 * @param result the final result, when completed
 * @param error the failure message, when errored
 */
public record ExecutorCallback(
    String executionId, String status, Integer suspendGeneration, JsonNode result, String error) {

  public static ExecutorCallback completed(String executionId, Integer suspendGeneration, JsonNode result) {
    return new ExecutorCallback(executionId, "COMPLETED", suspendGeneration, result, null);
  }

  public static ExecutorCallback failed(String executionId, Integer suspendGeneration, String error) {
    return new ExecutorCallback(executionId, "ERROR", suspendGeneration, null, error);
  }
}
