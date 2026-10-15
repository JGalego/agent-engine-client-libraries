package com.mongodb.agentengine.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The parts of {@code agent.yaml} the runner reads.
 *
 * @param entrypoint where the app lives, as {@code fully.qualified.Class:member}
 * @param name the agent's name, if set
 * @param framework the agent framework, if set
 * @param features the explicitly declared feature flags
 */
public record AgentConfig(String entrypoint, String name, String framework, Map<String, Boolean> features) {

  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

  /**
   * Reads an {@code agent.yaml} file.
   *
   * @throws IOException if the file cannot be read or parsed
   * @throws IllegalArgumentException if it is not a mapping with an {@code entrypoint}
   */
  public static AgentConfig load(Path path) throws IOException {
    if (!Files.exists(path)) {
      throw new IOException("Agent config not found: " + path.toAbsolutePath());
    }
    return parse(YAML.readTree(Files.readString(path)));
  }

  static AgentConfig parse(JsonNode root) {
    if (root == null || !root.isObject() || root.isEmpty()) {
      throw new IllegalArgumentException("Invalid agent.yaml: it must contain a mapping with an 'entrypoint'");
    }
    JsonNode entrypoint = root.get("entrypoint");
    if (entrypoint == null || !entrypoint.isTextual() || entrypoint.asText().isBlank()) {
      throw new IllegalArgumentException("Invalid agent.yaml: missing required 'entrypoint' field");
    }
    Map<String, Boolean> features = new LinkedHashMap<>();
    JsonNode declared = root.get("features");
    if (declared != null && declared.isObject()) {
      declared.fields().forEachRemaining(entry -> {
        if (entry.getValue().isBoolean()) {
          features.put(entry.getKey(), entry.getValue().asBoolean());
        }
      });
    }
    return new AgentConfig(entrypoint.asText().trim(), text(root, "name"), text(root, "framework"), Map.copyOf(features));
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value != null && value.isTextual() ? value.asText() : null;
  }
}
