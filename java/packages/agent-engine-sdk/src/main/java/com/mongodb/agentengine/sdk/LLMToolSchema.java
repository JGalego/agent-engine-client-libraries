package com.mongodb.agentengine.sdk;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Serializable bound-tool schema forwarded with an LLM call. The shape follows whichever provider
 * format the caller bound, so every field is optional.
 */
public record LLMToolSchema(
    String name,
    String description,
    JsonNode parameters,
    String type,
    Boolean strict,
    JsonNode function) {}
