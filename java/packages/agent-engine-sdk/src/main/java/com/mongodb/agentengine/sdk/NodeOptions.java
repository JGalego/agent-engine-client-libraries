package com.mongodb.agentengine.sdk;

import java.util.Map;

/**
 * Details of the node run an execution callback reports on.
 *
 * @param runId identifies this node run
 * @param parentRunId the enclosing run, if any
 * @param durationMs how long the run took; set only on completion
 * @param metadata framework-specific details
 */
public record NodeOptions(
    String runId, String parentRunId, Long durationMs, Map<String, Object> metadata) {}
