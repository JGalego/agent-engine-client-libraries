package com.mongodb.agentengine.sdk.api.v1.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.mongodb.agentengine.sdk.Json;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ContextConfigTest {

  @Test
  void defaults() {
    ContextConfig config = ContextConfig.defaults();

    assertEquals(16000, config.maxTokens());
    assertEquals(500, config.tokenBuffer());
    assertEquals(FormatStyle.OPENAI, config.formatStyle());
    assertEquals(ModelType.OPENAI, config.modelType());
    assertEquals(0.0, config.similarityThreshold());
    assertEquals(List.of(MemorySource.EPISODIC, MemorySource.SEMANTIC), List.copyOf(config.enabledSources()));
    assertEquals(false, config.includeMemories());
  }

  @Test
  void parsesWireValuesCaseInsensitivelyForStyleAndModel() throws Exception {
    ContextConfig config =
        Json.mapper()
            .readValue(
                "{\"format_style\":\"CLAUDE\",\"model_type\":\"Gemini\",\"enabled_sources\":[\"stm\",\"stm\",\"semantic\"],"
                    + "\"max_tokens\":100}",
                ContextConfig.class);

    assertEquals(FormatStyle.CLAUDE, config.formatStyle());
    assertEquals(ModelType.GEMINI, config.modelType());
    assertEquals(Set.of(MemorySource.STM, MemorySource.SEMANTIC), config.enabledSources());
    assertEquals(100, config.maxTokens());
  }

  @Test
  void sourcesStayStrictlyLowercase() {
    assertThrows(Exception.class, () -> Json.mapper().readValue("{\"enabled_sources\":[\"STM\"]}", ContextConfig.class));
  }

  @Test
  void rejectsOutOfRangeValues() {
    assertThrows(IllegalArgumentException.class, () -> new ContextConfig(0, null, null, null, null, null, null, null));
    assertThrows(IllegalArgumentException.class, () -> new ContextConfig(null, -1, null, null, null, null, null, null));
    assertThrows(IllegalArgumentException.class, () -> new ContextConfig(null, null, null, null, 1.5, null, null, null));
    assertThrows(IllegalArgumentException.class, () -> new ContextConfig(null, null, null, null, null, null, Set.of(), null));
  }

  @Test
  void serializesWireValues() {
    var wire = Json.mapper().valueToTree(ContextConfig.defaults());

    assertEquals("openai", wire.get("format_style").asText());
    assertEquals("episodic", wire.get("enabled_sources").get(0).asText());
  }
}
