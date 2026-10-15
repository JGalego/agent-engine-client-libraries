package com.mongodb.agentengine.runner.models;

import java.util.List;
import java.util.Map;

/** The tools an app has registered. */
public record ToolsListResponse(List<Map<String, Object>> tools, Integer count) {}
