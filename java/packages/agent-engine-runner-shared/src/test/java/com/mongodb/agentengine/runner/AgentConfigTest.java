package com.mongodb.agentengine.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentConfigTest {

  @TempDir Path dir;

  private AgentConfig load(String yaml) throws IOException {
    Path file = dir.resolve("agent.yaml");
    Files.writeString(file, yaml);
    return AgentConfig.load(file);
  }

  @Test
  void readsTheFieldsTheRunnerUses() throws Exception {
    AgentConfig config =
        load("name: hello\nentrypoint: ' com.example.App:app '\nframework: langgraph4j\nfeatures:\n  memory: true\n  durable_workflow: false\n  other: text\nsandboxes:\n  agent: {}\n");

    assertEquals("com.example.App:app", config.entrypoint());
    assertEquals("hello", config.name());
    assertEquals("langgraph4j", config.framework());
    assertEquals(java.util.Map.of("memory", true, "durable_workflow", false), config.features());
  }

  @Test
  void onlyTheEntrypointIsRequired() throws Exception {
    AgentConfig config = load("entrypoint: com.example.App:app\n");

    assertNull(config.name());
    assertNull(config.framework());
    assertEquals(java.util.Map.of(), config.features());
  }

  @Test
  void aMissingEntrypointIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> load("name: hello\n"));
    assertThrows(IllegalArgumentException.class, () -> load("entrypoint: ''\n"));
    assertThrows(IllegalArgumentException.class, () -> load("- a list\n"));
  }

  @Test
  void aMissingFileIsAnIoError() {
    assertThrows(IOException.class, () -> AgentConfig.load(dir.resolve("nope.yaml")));
  }
}
