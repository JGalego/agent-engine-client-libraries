package com.mongodb.agentengine.sdk.api.v1.memory;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A procedural memory (a how-to) to save.
 *
 * @param procedure the procedure's unique name
 * @param userId required by the server; the {@code Memory} facade fills it from the identity
 *     context when omitted
 * @param visibility default {@code private}
 */
public record CreateProceduralRequest(
    String procedure,
    String description,
    String content,
    String userId,
    String agentId,
    List<Map<String, Object>> steps,
    List<Map<String, Object>> resources,
    List<String> allowedTools,
    String compatibility,
    String license,
    List<String> triggerConditions,
    List<String> tags,
    String visibility,
    String extractionSource,
    String sourceFormat,
    String sourcePath) {

  public CreateProceduralRequest {
    Objects.requireNonNull(procedure, "procedure");
    Objects.requireNonNull(description, "description");
    Objects.requireNonNull(content, "content");
    steps = Immutable.list(steps);
    resources = Immutable.list(resources);
    allowedTools = Immutable.list(allowedTools);
    triggerConditions = Immutable.list(triggerConditions);
    tags = Immutable.list(tags);
  }

  /** Starts a request with its required fields. */
  public static Builder builder(String procedure, String description, String content) {
    return new Builder(procedure, description, content);
  }

  /** Returns a builder initialized from this request. */
  public Builder toBuilder() {
    Builder b = new Builder(procedure, description, content);
    b.userId = userId;
    b.agentId = agentId;
    b.steps = steps;
    b.resources = resources;
    b.allowedTools = allowedTools;
    b.compatibility = compatibility;
    b.license = license;
    b.triggerConditions = triggerConditions;
    b.tags = tags;
    b.visibility = visibility;
    b.extractionSource = extractionSource;
    b.sourceFormat = sourceFormat;
    b.sourcePath = sourcePath;
    return b;
  }

  /** Builder for {@link CreateProceduralRequest}. */
  public static final class Builder {
    private final String procedure;
    private final String description;
    private final String content;
    private String userId;
    private String agentId;
    private List<Map<String, Object>> steps;
    private List<Map<String, Object>> resources;
    private List<String> allowedTools;
    private String compatibility;
    private String license;
    private List<String> triggerConditions;
    private List<String> tags;
    private String visibility;
    private String extractionSource;
    private String sourceFormat;
    private String sourcePath;

    private Builder(String procedure, String description, String content) {
      this.procedure = procedure;
      this.description = description;
      this.content = content;
    }

    public Builder userId(String v) { userId = v; return this; }
    public Builder agentId(String v) { agentId = v; return this; }
    public Builder steps(List<Map<String, Object>> v) { steps = v; return this; }
    public Builder resources(List<Map<String, Object>> v) { resources = v; return this; }
    public Builder allowedTools(List<String> v) { allowedTools = v; return this; }
    public Builder compatibility(String v) { compatibility = v; return this; }
    public Builder license(String v) { license = v; return this; }
    public Builder triggerConditions(List<String> v) { triggerConditions = v; return this; }
    public Builder tags(List<String> v) { tags = v; return this; }
    public Builder visibility(String v) { visibility = v; return this; }
    public Builder extractionSource(String v) { extractionSource = v; return this; }
    public Builder sourceFormat(String v) { sourceFormat = v; return this; }
    public Builder sourcePath(String v) { sourcePath = v; return this; }

    public CreateProceduralRequest build() {
      return new CreateProceduralRequest(
          procedure, description, content, userId, agentId, steps, resources, allowedTools,
          compatibility, license, triggerConditions, tags, visibility, extractionSource,
          sourceFormat, sourcePath);
    }
  }
}
