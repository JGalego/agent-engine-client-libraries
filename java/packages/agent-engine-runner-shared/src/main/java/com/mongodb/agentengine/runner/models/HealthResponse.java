package com.mongodb.agentengine.runner.models;

import java.util.Map;

/**
 * A server's health, which probes read from the HTTP status rather than from this body.
 *
 * @param status {@code healthy}, {@code degraded} or {@code unhealthy}
 * @param component the app's name
 * @param mode {@code aer} or {@code tool}
 * @param details mode-specific details
 */
public record HealthResponse(String status, String component, String mode, String version, Map<String, Object> details) {}
