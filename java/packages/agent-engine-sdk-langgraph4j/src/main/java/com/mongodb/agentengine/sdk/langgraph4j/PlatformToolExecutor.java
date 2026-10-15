package com.mongodb.agentengine.sdk.langgraph4j;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.agentengine.runner.RunnableApp;
import com.mongodb.agentengine.runner.ToolExecutionException;
import com.mongodb.agentengine.sdk.Json;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecutor;
import java.util.Map;

/**
 * A LangChain4j tool executor that runs the tool through the orchestration engine.
 *
 * <p>The tool is looked up by name among the tools registered on the app, so what the OE approves,
 * records and runs is the registered {@code ToolDefinition}, never code the graph holds itself. A
 * tool that fails answers with its error message, so the model can react to it, the way LangGraph's
 * own tool node does; a call the OE refuses ends the run with an exception.
 */
public final class PlatformToolExecutor implements ToolExecutor {

  private final RunnableApp app;

  public PlatformToolExecutor(RunnableApp app) {
    this.app = app;
  }

  @Override
  public String execute(ToolExecutionRequest request, Object memoryId) {
    try {
      JsonNode result = app.callTool(request.name(), arguments(request), request.id());
      return result.isTextual() ? result.asText() : result.toString();
    } catch (ToolExecutionException e) {
      return "Error: " + e.getMessage();
    }
  }

  private static Map<String, Object> arguments(ToolExecutionRequest request) {
    String json = request.arguments();
    if (json == null || json.isBlank()) {
      return Map.of();
    }
    try {
      return Json.mapper().readValue(json, new TypeReference<Map<String, Object>>() {});
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Tool arguments for '" + request.name() + "' are not a JSON object: " + e.getOriginalMessage(), e);
    }
  }
}
