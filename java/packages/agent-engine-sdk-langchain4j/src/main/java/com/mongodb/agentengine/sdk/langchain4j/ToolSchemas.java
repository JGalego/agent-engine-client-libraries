package com.mongodb.agentengine.sdk.langchain4j;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mongodb.agentengine.sdk.Json;
import com.mongodb.agentengine.sdk.LLMToolSchema;
import com.mongodb.agentengine.sdk.ToolDefinition;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonAnyOfSchema;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNullSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonRawSchema;
import dev.langchain4j.model.chat.request.json.JsonReferenceSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Translation between platform tool schemas (JSON Schema) and LangChain4j {@link
 * ToolSpecification}s.
 *
 * <p>LangChain4j models the common JSON Schema vocabulary: objects, arrays, scalars, enums, {@code
 * anyOf} and {@code $ref}. A subschema outside it (for example {@code allOf}) is forwarded as a raw
 * schema rather than rejected, and it is up to the provider to accept it.
 */
public final class ToolSchemas {

  private static final String DEFS = "$defs";

  private ToolSchemas() {}

  /** Builds a LangChain4j specification from a platform tool definition. */
  public static ToolSpecification toToolSpecification(ToolDefinition tool) {
    return ToolSpecification.builder()
        .name(tool.name())
        .description(tool.description())
        .parameters(toObjectSchema(Json.mapper().valueToTree(tool.argsSchema())))
        .build();
  }

  /**
   * Builds a LangChain4j specification from a bound-tool schema. Both the flat form and the OpenAI
   * form, which nests the tool under {@code function}, are accepted.
   *
   * @throws IllegalArgumentException if the schema has no tool name
   */
  public static ToolSpecification toToolSpecification(LLMToolSchema schema) {
    JsonNode function = schema.function();
    boolean nested = function != null && function.isObject();
    String name = nested ? text(function, "name") : schema.name();
    if (name == null) {
      throw new IllegalArgumentException("Tool schema has no name");
    }
    String description = nested ? text(function, "description") : schema.description();
    JsonNode parameters = nested ? function.get("parameters") : schema.parameters();
    return ToolSpecification.builder()
        .name(name)
        .description(description)
        .parameters(toObjectSchema(parameters))
        .strict(schema.strict())
        .build();
  }

  /** Builds a platform tool schema from a LangChain4j specification. */
  public static LLMToolSchema toToolSchema(ToolSpecification specification) {
    JsonNode parameters = specification.parameters() == null ? null : toJsonSchema(specification.parameters());
    return new LLMToolSchema(
        specification.name(), specification.description(), parameters, null, specification.strict(), null);
  }

  // ---------------------------------------------------------------------------
  // JSON Schema -> LangChain4j
  // ---------------------------------------------------------------------------

  /** Converts the root schema of a tool's arguments. A missing or empty schema means no arguments. */
  static JsonObjectSchema toObjectSchema(JsonNode schema) {
    if (schema == null || schema.isNull() || schema.isEmpty()) {
      return JsonObjectSchema.builder().build();
    }
    JsonSchemaElement element = toElement(schema);
    if (element instanceof JsonObjectSchema object) {
      return object;
    }
    throw new IllegalArgumentException("Tool arguments must be described by an object schema");
  }

  private static JsonSchemaElement toElement(JsonNode schema) {
    if (schema.has("$ref")) {
      String ref = schema.get("$ref").asText();
      return JsonReferenceSchema.builder().reference(ref.substring(ref.lastIndexOf('/') + 1)).build();
    }
    for (String keyword : List.of("anyOf", "oneOf")) {
      if (schema.has(keyword)) {
        return anyOf(schema, schema.get(keyword));
      }
    }
    JsonNode type = schema.get("type");
    if (type != null && type.isArray()) {
      List<JsonSchemaElement> options = new ArrayList<>();
      for (JsonNode single : type) {
        ObjectNode copy = schema.deepCopy();
        copy.set("type", single);
        options.add(toElement(copy));
      }
      return JsonAnyOfSchema.builder().anyOf(options).description(text(schema, "description")).build();
    }
    String description = text(schema, "description");
    String kind = type == null ? inferKind(schema) : type.asText();
    return switch (kind) {
      case "object" -> object(schema, description);
      case "array" -> {
        var builder = JsonArraySchema.builder().description(description);
        if (schema.has("items")) {
          builder.items(toElement(schema.get("items")));
        }
        yield builder.build();
      }
      case "string" -> schema.has("enum") ? enumOf(schema, description) : JsonStringSchema.builder().description(description).build();
      case "integer" -> JsonIntegerSchema.builder().description(description).build();
      case "number" -> JsonNumberSchema.builder().description(description).build();
      case "boolean" -> JsonBooleanSchema.builder().description(description).build();
      case "null" -> new JsonNullSchema();
      default -> JsonRawSchema.from(schema.toString());
    };
  }

  private static String inferKind(JsonNode schema) {
    if (schema.has("properties")) {
      return "object";
    }
    return schema.has("enum") ? "string" : "";
  }

  private static JsonSchemaElement anyOf(JsonNode schema, JsonNode options) {
    List<JsonSchemaElement> elements = new ArrayList<>();
    for (JsonNode option : options) {
      elements.add(toElement(option));
    }
    return JsonAnyOfSchema.builder().anyOf(elements).description(text(schema, "description")).build();
  }

  private static JsonSchemaElement enumOf(JsonNode schema, String description) {
    List<String> values = new ArrayList<>();
    for (JsonNode value : schema.get("enum")) {
      values.add(value.asText());
    }
    return JsonEnumSchema.builder().enumValues(values).description(description).build();
  }

  private static JsonObjectSchema object(JsonNode schema, String description) {
    var builder = JsonObjectSchema.builder().description(description);
    JsonNode properties = schema.get("properties");
    if (properties != null) {
      properties.fields().forEachRemaining(entry -> builder.addProperty(entry.getKey(), toElement(entry.getValue())));
    }
    JsonNode required = schema.get("required");
    if (required != null && required.isArray()) {
      List<String> names = new ArrayList<>();
      required.forEach(name -> names.add(name.asText()));
      builder.required(names);
    }
    JsonNode additional = schema.get("additionalProperties");
    if (additional != null && additional.isBoolean()) {
      builder.additionalProperties(additional.asBoolean());
    }
    for (String key : List.of(DEFS, "definitions")) {
      JsonNode defs = schema.get(key);
      if (defs != null && defs.isObject()) {
        Map<String, JsonSchemaElement> definitions = new LinkedHashMap<>();
        defs.fields().forEachRemaining(entry -> definitions.put(entry.getKey(), toElement(entry.getValue())));
        builder.definitions(definitions);
      }
    }
    return builder.build();
  }

  // ---------------------------------------------------------------------------
  // LangChain4j -> JSON Schema
  // ---------------------------------------------------------------------------

  static JsonNode toJsonSchema(JsonSchemaElement element) {
    JsonNodeFactory f = JsonNodeFactory.instance;
    ObjectNode node = f.objectNode();
    if (element instanceof JsonObjectSchema object) {
      node.put("type", "object");
      ObjectNode properties = node.putObject("properties");
      if (object.properties() != null) {
        object.properties().forEach((name, property) -> properties.set(name, toJsonSchema(property)));
      }
      if (object.required() != null && !object.required().isEmpty()) {
        ArrayNode required = node.putArray("required");
        object.required().forEach(required::add);
      }
      if (object.additionalProperties() != null) {
        node.put("additionalProperties", object.additionalProperties());
      }
      if (object.definitions() != null && !object.definitions().isEmpty()) {
        ObjectNode defs = node.putObject(DEFS);
        object.definitions().forEach((name, definition) -> defs.set(name, toJsonSchema(definition)));
      }
    } else if (element instanceof JsonArraySchema array) {
      node.put("type", "array");
      if (array.items() != null) {
        node.set("items", toJsonSchema(array.items()));
      }
    } else if (element instanceof JsonEnumSchema enumeration) {
      node.put("type", "string");
      ArrayNode values = node.putArray("enum");
      enumeration.enumValues().forEach(values::add);
    } else if (element instanceof JsonAnyOfSchema anyOf) {
      ArrayNode options = node.putArray("anyOf");
      anyOf.anyOf().forEach(option -> options.add(toJsonSchema(option)));
    } else if (element instanceof JsonReferenceSchema reference) {
      node.put("$ref", "#/" + DEFS + "/" + reference.reference());
    } else if (element instanceof JsonRawSchema raw) {
      try {
        return Json.mapper().readTree(raw.schema());
      } catch (JsonProcessingException e) {
        throw new IllegalArgumentException("Raw schema is not valid JSON", e);
      }
    } else {
      node.put("type", scalarType(element));
    }
    if (element.description() != null && !(element instanceof JsonReferenceSchema)) {
      node.put("description", element.description());
    }
    return node;
  }

  private static String scalarType(JsonSchemaElement element) {
    if (element instanceof JsonStringSchema) return "string";
    if (element instanceof JsonIntegerSchema) return "integer";
    if (element instanceof JsonNumberSchema) return "number";
    if (element instanceof JsonBooleanSchema) return "boolean";
    if (element instanceof JsonNullSchema) return "null";
    throw new IllegalArgumentException("Unsupported schema element: " + element.getClass().getSimpleName());
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value != null && value.isTextual() ? value.asText() : null;
  }
}
