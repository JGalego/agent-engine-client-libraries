package com.mongodb.agentengine.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RunnerConfigTest {

  @Test
  void defaultsHostBothServersOnTheirStandardPorts() {
    RunnerConfig config = RunnerConfig.fromEnv(Map.of());

    assertEquals(List.of(RunnerMode.AER, RunnerMode.TOOL), config.orderedModes());
    assertEquals(8001, config.portFor(RunnerMode.AER));
    assertEquals(8002, config.portFor(RunnerMode.TOOL));
    assertEquals("0.0.0.0", config.host());
    assertEquals("agent.yaml", config.agentConfigPath());
    assertEquals("", config.oeUrl());
    assertNull(config.portOverride());
  }

  @Test
  void runnerModeWinsOverTheDevModeList() {
    RunnerConfig config = RunnerConfig.fromEnv(Map.of("RUNNER_MODE", "tool", "AGENTIC_DEV_MODES", "aer,tool"));

    assertEquals(List.of(RunnerMode.TOOL), config.orderedModes());
  }

  @Test
  void aSingleModeTakesTheAppPort() {
    RunnerConfig config = RunnerConfig.fromEnv(Map.of("RUNNER_MODE", "aer", "APP_PORT", "9000"));

    assertEquals(9000, config.portFor(RunnerMode.AER));
  }

  @Test
  void anAppPortNeedsASingleMode() {
    assertThrows(IllegalArgumentException.class, () -> RunnerConfig.fromEnv(Map.of("APP_PORT", "9000")));
  }

  @Test
  void unknownModesAreRejected() {
    assertThrows(IllegalArgumentException.class, () -> RunnerConfig.fromEnv(Map.of("RUNNER_MODE", "memory")));
  }

  @Test
  void platformIdentityIsTrimmed() {
    RunnerConfig config =
        RunnerConfig.fromEnv(Map.of("OE_URL", " http://oe:8000 ", "APP_ID", " ws1 ", "ORG_ID", "org", "PROJECT_ID", "proj", "AGENT_CONFIG", "/etc/agent.yaml"));

    assertEquals("http://oe:8000", config.oeUrl());
    assertEquals("ws1", config.workspaceId());
    assertEquals("/etc/agent.yaml", config.agentConfigPath());
  }

  @Test
  void modeNamesParseCaseInsensitively() {
    assertEquals(RunnerMode.TOOL, RunnerMode.parse(" Tool "));
  }
}
