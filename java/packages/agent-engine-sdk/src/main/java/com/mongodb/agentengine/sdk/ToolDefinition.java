package com.mongodb.agentengine.sdk;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Framework-neutral tool definition.
 *
 * @param name the tool name
 * @param description what the tool does, shown to the model
 * @param argsSchema JSON Schema of the tool arguments
 * @param callable the implementation; never serialized
 * @param remote whether the tool runs in the sandboxed tool pod rather than in the agent process
 * @param providerType the provider the tool belongs to, if any
 * @param scopes scopes the tool requires
 * @param network hosts the tool may reach
 * @param timeoutSeconds how long a call may run
 * @param redactFields argument fields to redact from logs
 */
public record ToolDefinition(
    String name,
    String description,
    Map<String, Object> argsSchema,
    @JsonIgnore ToolCallable callable,
    boolean remote,
    String providerType,
    List<String> scopes,
    List<String> network,
    int timeoutSeconds,
    List<String> redactFields) {

  public ToolDefinition {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(description, "description");
    Objects.requireNonNull(argsSchema, "argsSchema");
    Objects.requireNonNull(callable, "callable");
    scopes = List.copyOf(scopes);
    network = List.copyOf(network);
    redactFields = List.copyOf(redactFields);
  }

  /** Starts a definition with the platform defaults: remote, no scopes, 30 second timeout. */
  public static Builder builder(
      String name, String description, Map<String, Object> argsSchema, ToolCallable callable) {
    return new Builder(name, description, argsSchema, callable);
  }

  /** Builder for {@link ToolDefinition}. */
  public static final class Builder {
    private final String name;
    private final String description;
    private final Map<String, Object> argsSchema;
    private final ToolCallable callable;
    private boolean remote = true;
    private String providerType;
    private List<String> scopes = List.of();
    private List<String> network = List.of();
    private int timeoutSeconds = 30;
    private List<String> redactFields = List.of();

    private Builder(
        String name, String description, Map<String, Object> argsSchema, ToolCallable callable) {
      this.name = name;
      this.description = description;
      this.argsSchema = argsSchema;
      this.callable = callable;
    }

    public Builder remote(boolean v) { remote = v; return this; }
    public Builder providerType(String v) { providerType = v; return this; }
    public Builder scopes(List<String> v) { scopes = v; return this; }
    public Builder network(List<String> v) { network = v; return this; }
    public Builder timeoutSeconds(int v) { timeoutSeconds = v; return this; }
    public Builder redactFields(List<String> v) { redactFields = v; return this; }

    public ToolDefinition build() {
      return new ToolDefinition(
          name, description, argsSchema, callable, remote, providerType, scopes, network,
          timeoutSeconds, redactFields);
    }
  }
}
