package com.mongodb.agentengine.runner.models;

/**
 * The runtime's answer to an execute request.
 *
 * @param status {@code completed}, {@code suspended} or {@code cancelled}
 * @param result the final response text, when completed
 * @param suspendReason why the agent suspended, when suspended
 */
public record AerExecuteResponse(String status, String result, String suspendReason) {

  public static AerExecuteResponse completed(String result) {
    return new AerExecuteResponse("completed", result, null);
  }
}
