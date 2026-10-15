/**
 * Framework-neutral protocols and models for the Atlas Agent Engine SDK.
 *
 * <p>Models are immutable records that serialize to the platform's snake_case JSON wire format
 * through {@link com.mongodb.agentengine.sdk.Json#mapper()}. Opaque JSON values (agent payloads,
 * tool arguments) are Jackson {@link com.fasterxml.jackson.databind.JsonNode}s.
 */
package com.mongodb.agentengine.sdk;
