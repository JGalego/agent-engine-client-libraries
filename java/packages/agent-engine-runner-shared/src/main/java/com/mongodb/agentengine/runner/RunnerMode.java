package com.mongodb.agentengine.runner;

import java.util.Locale;

/** Which server a runner process hosts. */
public enum RunnerMode {

  /** The agent runtime, which executes agent turns. */
  AER("aer", 8001),

  /** The tool server, which runs remote tool bodies and LLM calls. */
  TOOL("tool", 8002);

  private final String wireName;
  private final int defaultPort;

  RunnerMode(String wireName, int defaultPort) {
    this.wireName = wireName;
    this.defaultPort = defaultPort;
  }

  /** The mode's name as it appears in environment variables and health responses. */
  public String wireName() {
    return wireName;
  }

  /** The port the platform expects the mode on. */
  public int defaultPort() {
    return defaultPort;
  }

  /**
   * Parses a mode name.
   *
   * @throws IllegalArgumentException if the name is not a known mode
   */
  public static RunnerMode parse(String name) {
    String normalized = name.trim().toLowerCase(Locale.ROOT);
    for (RunnerMode mode : values()) {
      if (mode.wireName.equals(normalized)) {
        return mode;
      }
    }
    throw new IllegalArgumentException("Unsupported runner mode: " + name);
  }
}
