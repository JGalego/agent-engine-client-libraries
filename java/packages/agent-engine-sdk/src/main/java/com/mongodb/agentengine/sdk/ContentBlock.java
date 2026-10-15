package com.mongodb.agentengine.sdk;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * A piece of multimodal message content.
 *
 * <p>Blocks are discriminated by their {@code type} field, so a document block is never mistaken
 * for an image block that happens to share a {@code url}.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = TextBlock.class, name = "text"),
  @JsonSubTypes.Type(value = ImageBlock.class, name = "image"),
  @JsonSubTypes.Type(value = DocumentBlock.class, name = "document")
})
public sealed interface ContentBlock permits TextBlock, ImageBlock, DocumentBlock {}
