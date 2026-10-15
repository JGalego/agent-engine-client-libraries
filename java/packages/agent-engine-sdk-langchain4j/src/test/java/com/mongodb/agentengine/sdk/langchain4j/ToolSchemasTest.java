package com.mongodb.agentengine.sdk.langchain4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.agentengine.sdk.Json;
import com.mongodb.agentengine.sdk.LLMToolSchema;
import com.mongodb.agentengine.sdk.ToolDefinition;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonAnyOfSchema;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonRawSchema;
import dev.langchain4j.model.chat.request.json.JsonReferenceSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolSchemasTest {

  private static JsonNode json(String text) throws Exception {
    return Json.mapper().readTree(text);
  }

  private static final String WEATHER =
      "{\"type\":\"object\",\"description\":\"args\",\"properties\":{"
          + "\"city\":{\"type\":\"string\",\"description\":\"City name\"},"
          + "\"days\":{\"type\":\"integer\"},"
          + "\"unit\":{\"type\":\"string\",\"enum\":[\"c\",\"f\"]},"
          + "\"tags\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}},"
          + "\"required\":[\"city\"],\"additionalProperties\":false}";

  @Test
  void convertsCommonSchemaVocabulary() throws Exception {
    JsonObjectSchema schema = ToolSchemas.toObjectSchema(json(WEATHER));

    assertEquals("args", schema.description());
    assertEquals(java.util.List.of("city"), schema.required());
    assertEquals(false, schema.additionalProperties());
    assertEquals("City name", assertInstanceOf(JsonStringSchema.class, schema.properties().get("city")).description());
    assertInstanceOf(JsonIntegerSchema.class, schema.properties().get("days"));
    assertEquals(java.util.List.of("c", "f"), assertInstanceOf(JsonEnumSchema.class, schema.properties().get("unit")).enumValues());
    assertInstanceOf(JsonStringSchema.class, assertInstanceOf(JsonArraySchema.class, schema.properties().get("tags")).items());
  }

  @Test
  void schemaSurvivesARoundTrip() throws Exception {
    JsonNode original = json(WEATHER);

    JsonNode back = ToolSchemas.toJsonSchema(ToolSchemas.toObjectSchema(original));

    assertEquals(original, back);
  }

  @Test
  void referencesAndDefinitionsSurviveARoundTrip() throws Exception {
    JsonNode original =
        json(
            "{\"type\":\"object\",\"properties\":{\"home\":{\"$ref\":\"#/$defs/Address\"}},"
                + "\"$defs\":{\"Address\":{\"type\":\"object\",\"properties\":{\"zip\":{\"type\":\"string\"}}}}}");

    JsonObjectSchema schema = ToolSchemas.toObjectSchema(original);

    assertEquals("Address", assertInstanceOf(JsonReferenceSchema.class, schema.properties().get("home")).reference());
    assertInstanceOf(JsonObjectSchema.class, schema.definitions().get("Address"));
    assertEquals(original.get("$defs"), ToolSchemas.toJsonSchema(schema).get("$defs"));
    assertEquals("#/$defs/Address", ToolSchemas.toJsonSchema(schema).get("properties").get("home").get("$ref").asText());
  }

  @Test
  void unionsBecomeAnyOf() throws Exception {
    JsonObjectSchema schema =
        ToolSchemas.toObjectSchema(
            json(
                "{\"type\":\"object\",\"properties\":{"
                    + "\"a\":{\"anyOf\":[{\"type\":\"string\"},{\"type\":\"integer\"}]},"
                    + "\"b\":{\"type\":[\"string\",\"null\"]},"
                    + "\"c\":{\"oneOf\":[{\"type\":\"boolean\"}]}}}"));

    assertEquals(2, assertInstanceOf(JsonAnyOfSchema.class, schema.properties().get("a")).anyOf().size());
    assertEquals(2, assertInstanceOf(JsonAnyOfSchema.class, schema.properties().get("b")).anyOf().size());
    assertEquals(1, assertInstanceOf(JsonAnyOfSchema.class, schema.properties().get("c")).anyOf().size());
  }

  @Test
  void vocabularyOutsideTheModelIsForwardedRaw() throws Exception {
    JsonObjectSchema schema =
        ToolSchemas.toObjectSchema(json("{\"type\":\"object\",\"properties\":{\"x\":{\"allOf\":[{\"type\":\"string\"}]}}}"));

    assertInstanceOf(JsonRawSchema.class, schema.properties().get("x"));
    assertEquals(json("{\"allOf\":[{\"type\":\"string\"}]}"), ToolSchemas.toJsonSchema(schema.properties().get("x")));
  }

  @Test
  void aMissingOrEmptySchemaMeansNoArguments() throws Exception {
    assertTrue(ToolSchemas.toObjectSchema(null).properties().isEmpty());
    assertTrue(ToolSchemas.toObjectSchema(json("{}")).properties().isEmpty());
  }

  @Test
  void aNonObjectRootIsRejected() throws Exception {
    assertThrows(IllegalArgumentException.class, () -> ToolSchemas.toObjectSchema(json("{\"type\":\"string\"}")));
  }

  @Test
  void toolDefinitionsBecomeSpecifications() throws Exception {
    Map<String, Object> args = Json.mapper().convertValue(json(WEATHER), new com.fasterxml.jackson.core.type.TypeReference<>() {});
    ToolDefinition tool = ToolDefinition.builder("get_weather", "Gets the weather", args, a -> "sunny").build();

    ToolSpecification spec = ToolSchemas.toToolSpecification(tool);

    assertEquals("get_weather", spec.name());
    assertEquals("Gets the weather", spec.description());
    assertTrue(spec.parameters().properties().containsKey("city"));
  }

  @Test
  void flatAndNestedBoundToolSchemasAreAccepted() throws Exception {
    JsonNode params = json("{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}");
    var flat = new LLMToolSchema("search", "Searches", params, null, true, null);
    var nested =
        new LLMToolSchema(null, null, null, "function", null, json("{\"name\":\"search\",\"description\":\"Searches\",\"parameters\":" + params + "}"));

    ToolSpecification a = ToolSchemas.toToolSpecification(flat);
    ToolSpecification b = ToolSchemas.toToolSpecification(nested);

    assertEquals("search", a.name());
    assertEquals(true, a.strict());
    assertEquals("search", b.name());
    assertEquals("Searches", b.description());
    assertTrue(b.parameters().properties().containsKey("q"));
  }

  @Test
  void aToolSchemaNeedsAName() {
    assertThrows(IllegalArgumentException.class, () -> ToolSchemas.toToolSpecification(new LLMToolSchema(null, "d", null, null, null, null)));
  }

  @Test
  void specificationsConvertBackToPlatformSchemas() throws Exception {
    ToolSpecification spec =
        ToolSpecification.builder().name("search").description("Searches").parameters(ToolSchemas.toObjectSchema(json(WEATHER))).build();

    LLMToolSchema schema = ToolSchemas.toToolSchema(spec);

    assertEquals("search", schema.name());
    assertEquals("Searches", schema.description());
    assertEquals(json(WEATHER), schema.parameters());
  }
}
