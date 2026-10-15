package com.mongodb.agentengine.sdk.api.v1.memory;

import java.util.Map;
import java.util.Objects;

/**
 * A memory of a custom type declared for the project.
 *
 * @param memoryType the declared type name
 * @param tags tag values, either scalars or one level of nested scalar groups
 */
public record CreateCustomRequest(
    String memoryType,
    String content,
    Map<String, Object> tags,
    Map<String, Object> contextualMetadata) {

  public CreateCustomRequest {
    Objects.requireNonNull(memoryType, "memoryType");
    Objects.requireNonNull(content, "content");
    tags = Immutable.map(tags);
    contextualMetadata = Immutable.map(contextualMetadata);
  }

  /** Starts a request with its required fields. */
  public static Builder builder(String memoryType, String content) {
    return new Builder(memoryType, content);
  }

  /** Returns a builder initialized from this request. */
  public Builder toBuilder() {
    Builder b = new Builder(memoryType, content);
    b.tags = tags;
    b.contextualMetadata = contextualMetadata;
    return b;
  }

  /** Builder for {@link CreateCustomRequest}. */
  public static final class Builder {
    private final String memoryType;
    private final String content;
    private Map<String, Object> tags;
    private Map<String, Object> contextualMetadata;

    private Builder(String memoryType, String content) {
      this.memoryType = memoryType;
      this.content = content;
    }

    public Builder tags(Map<String, Object> v) { tags = v; return this; }
    public Builder contextualMetadata(Map<String, Object> v) { contextualMetadata = v; return this; }

    public CreateCustomRequest build() {
      return new CreateCustomRequest(memoryType, content, tags, contextualMetadata);
    }
  }
}
