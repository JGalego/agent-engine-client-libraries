package com.mongodb.agentengine.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.mongodb.agentengine.runner.server.ToolServer;
import com.mongodb.agentengine.sdk.Json;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ToolServerTest {

  private ToolServer server;

  @BeforeEach
  void setUp() throws Exception {
    RunnerConfig config = RunnerConfig.fromEnv(Map.of("RUNNER_MODE", "tool", "APP_PORT", "0", "APP_HOST", "127.0.0.1"));
    server = new ToolServer(new RunnableApp("tools"), config);
    server.start();
  }

  @AfterEach
  void tearDown() {
    server.close();
  }

  @Test
  void healthReportsToolMode() throws Exception {
    var health = Json.mapper().readTree(TestHttp.get(server.port(), "/health").body());

    assertEquals("tool", health.get("mode").asText());
    assertEquals("tools", health.get("component").asText());
  }

  @Test
  void unimplementedRoutesAnswer501() {
    for (String path : new String[] {"/execute", "/invoke_llm", "/invoke_llm/stream", "/guardrails/check"}) {
      assertEquals(501, TestHttp.post(server.port(), path, "{}").status(), path);
    }
  }

  @Test
  void toolListingIsEmptyForAnAppWithoutTools() throws Exception {
    var tools = Json.mapper().readTree(TestHttp.get(server.port(), "/tools").body());

    assertEquals(0, tools.get("count").asInt());
  }
}
