package com.mongodb.agentengine.runner;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The runner's configuration, read from the environment the platform stamps on every container.
 *
 * @param modes the servers to host; local development hosts both in one process
 * @param host the interface to bind
 * @param portOverride the port to use when a single mode runs, or null for the mode's default
 * @param oeUrl the orchestration engine's URL, which callbacks always go to
 * @param workspaceId the deployed agent's workspace id ({@code APP_ID})
 * @param orgId the organization id
 * @param projectId the project id
 * @param agentConfigPath the path of {@code agent.yaml}
 */
public record RunnerConfig(
    Set<RunnerMode> modes,
    String host,
    Integer portOverride,
    String oeUrl,
    String workspaceId,
    String orgId,
    String projectId,
    String agentConfigPath) {

  /**
   * Reads the configuration. A process hosts the mode named by {@code RUNNER_MODE}, or else the
   * comma-separated {@code AGENTIC_DEV_MODES}, or else both modes.
   *
   * @throws IllegalArgumentException if a mode name or the port is invalid
   */
  public static RunnerConfig fromEnv(Map<String, String> env) {
    String modeList = first(env.get("RUNNER_MODE"), env.get("AGENTIC_DEV_MODES"), "aer,tool");
    Set<RunnerMode> modes = new LinkedHashSet<>();
    for (String name : modeList.split(",")) {
      if (!name.isBlank()) {
        modes.add(RunnerMode.parse(name));
      }
    }
    if (modes.isEmpty()) {
      throw new IllegalArgumentException("At least one runner mode is required");
    }
    String port = env.get("APP_PORT");
    if (port != null && !port.isBlank() && modes.size() > 1) {
      throw new IllegalArgumentException("APP_PORT needs a single runner mode, but " + modes.size() + " are configured");
    }
    return new RunnerConfig(
        modes,
        first(env.get("APP_HOST"), "0.0.0.0"),
        port == null || port.isBlank() ? null : Integer.valueOf(port.trim()),
        trimmed(env.get("OE_URL")),
        trimmed(env.get("APP_ID")),
        trimmed(env.get("ORG_ID")),
        trimmed(env.get("PROJECT_ID")),
        first(env.get("AGENT_CONFIG"), "agent.yaml"));
  }

  /** The port to bind for a mode. */
  public int portFor(RunnerMode mode) {
    return portOverride != null ? portOverride : mode.defaultPort();
  }

  /** The configured modes in a stable order. */
  public List<RunnerMode> orderedModes() {
    return new ArrayList<>(modes);
  }

  private static String first(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value.trim();
      }
    }
    return null;
  }

  private static String trimmed(String value) {
    return value == null ? "" : value.trim();
  }
}
