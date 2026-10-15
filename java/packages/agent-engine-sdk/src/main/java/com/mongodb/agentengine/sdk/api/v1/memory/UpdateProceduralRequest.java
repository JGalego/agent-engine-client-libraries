package com.mongodb.agentengine.sdk.api.v1.memory;

import java.util.List;
import java.util.Map;

/**
 * A change to a procedural memory, addressed by id or by procedure name.
 */
public record UpdateProceduralRequest(
    String id,
    String procedure,
    String description,
    String content,
    List<Map<String, Object>> steps,
    List<Map<String, Object>> resources,
    List<String> allowedTools,
    List<String> triggerConditions,
    List<String> tags,
    String visibility) {

  public UpdateProceduralRequest {
    steps = Immutable.list(steps);
    resources = Immutable.list(resources);
    allowedTools = Immutable.list(allowedTools);
    triggerConditions = Immutable.list(triggerConditions);
    tags = Immutable.list(tags);
  }

  /** Starts an empty request. */
  public static Builder builder() {
    return new Builder();
  }

  /** Returns a builder initialized from this request. */
  public Builder toBuilder() {
    Builder b = new Builder();
    b.id = id;
    b.procedure = procedure;
    b.description = description;
    b.content = content;
    b.steps = steps;
    b.resources = resources;
    b.allowedTools = allowedTools;
    b.triggerConditions = triggerConditions;
    b.tags = tags;
    b.visibility = visibility;
    return b;
  }

  /** Builder for {@link UpdateProceduralRequest}. */
  public static final class Builder {
    private String id;
    private String procedure;
    private String description;
    private String content;
    private List<Map<String, Object>> steps;
    private List<Map<String, Object>> resources;
    private List<String> allowedTools;
    private List<String> triggerConditions;
    private List<String> tags;
    private String visibility;

    private Builder() {}

    public Builder id(String v) { id = v; return this; }
    public Builder procedure(String v) { procedure = v; return this; }
    public Builder description(String v) { description = v; return this; }
    public Builder content(String v) { content = v; return this; }
    public Builder steps(List<Map<String, Object>> v) { steps = v; return this; }
    public Builder resources(List<Map<String, Object>> v) { resources = v; return this; }
    public Builder allowedTools(List<String> v) { allowedTools = v; return this; }
    public Builder triggerConditions(List<String> v) { triggerConditions = v; return this; }
    public Builder tags(List<String> v) { tags = v; return this; }
    public Builder visibility(String v) { visibility = v; return this; }

    public UpdateProceduralRequest build() {
      return new UpdateProceduralRequest(
          id, procedure, description, content, steps, resources, allowedTools,
          triggerConditions, tags, visibility);
    }
  }
}
