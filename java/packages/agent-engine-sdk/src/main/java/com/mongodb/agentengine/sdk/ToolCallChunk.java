package com.mongodb.agentengine.sdk;

/**
 * Partial tool call received while streaming. Any field may be absent: a chunk can carry just the
 * start of the arguments or just the tool name.
 *
 * @param args a fragment of the JSON-encoded arguments
 */
public record ToolCallChunk(String id, String name, String args, String type, Integer index) {}
