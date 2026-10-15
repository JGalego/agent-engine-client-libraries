package com.mongodb.agentengine.sdk;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * A final tool call requested by an LLM.
 *
 * @param id provider-assigned call id
 * @param name the tool to call
 * @param args the call arguments; some providers send them as {@code arguments}
 * @param type the call type, typically {@code "tool_call"}
 * @param index position of the call within the response
 */
public record LLMToolCall(
    String id, String name, @JsonAlias("arguments") JsonNode args, String type, Integer index) {}
