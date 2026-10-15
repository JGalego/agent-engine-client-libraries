package com.mongodb.agentengine.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ModelsTest {

  private static final ObjectMapper JSON = Json.mapper();

  private static JsonNode tree(String json) throws Exception {
    return JSON.readTree(json);
  }

  @Test
  void textMessageRoundTripsWithSnakeCaseFields() throws Exception {
    Message message =
        new Message(
            Role.TOOL, new MessageContent.Text("42"), null, "call_1", true, "calc", null, null, null);

    JsonNode wire = JSON.valueToTree(message);

    assertEquals(tree("{\"role\":\"tool\",\"content\":\"42\",\"tool_call_id\":\"call_1\","
        + "\"is_error\":true,\"name\":\"calc\"}"), wire);
    assertEquals(message, JSON.treeToValue(wire, Message.class));
  }

  @Test
  void multimodalContentKeepsBlockTypes() throws Exception {
    String wire =
        "{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"look\"},"
            + "{\"type\":\"document\",\"url\":\"s3://a\",\"filename\":\"a.pdf\"},"
            + "{\"type\":\"image\",\"url\":\"s3://b\",\"mime_type\":\"image/png\"}]}";

    Message message = JSON.readValue(wire, Message.class);

    var blocks = assertInstanceOf(MessageContent.Blocks.class, message.content()).blocks();
    assertEquals(new TextBlock("look"), blocks.get(0));
    assertEquals(new DocumentBlock("s3://a", null, "a.pdf"), blocks.get(1));
    assertEquals(new ImageBlock("s3://b", "image/png"), blocks.get(2));
    assertEquals(tree(wire), JSON.valueToTree(message));
  }

  @Test
  void contentMustBeStringOrArray() {
    assertThrows(Exception.class, () -> JSON.readValue("{\"role\":\"user\",\"content\":5}", Message.class));
  }

  @Test
  void roleMustBeKnown() {
    assertThrows(Exception.class, () -> JSON.readValue("{\"role\":\"robot\",\"content\":\"x\"}", Message.class));
  }

  @Test
  void tokenUsageDerivesTotal() {
    assertEquals(7, new LLMTokenUsage(3, 4, null, null, null, null, null).totalTokens());
    assertEquals(9, new LLMTokenUsage(null, null, 4, 5, null, null, null).totalTokens());
    assertEquals(100, new LLMTokenUsage(3, 4, null, null, 100, null, null).totalTokens());
    assertNull(new LLMTokenUsage(3, null, null, null, null, null, null).totalTokens());
  }

  @Test
  void toolCallAcceptsArgumentsAlias() throws Exception {
    LLMToolCall call = JSON.readValue("{\"id\":\"c\",\"name\":\"f\",\"arguments\":{\"a\":1}}", LLMToolCall.class);

    assertEquals(tree("{\"a\":1}"), call.args());
  }

  @Test
  void invocationOptionsKeepProviderSpecificExtras() throws Exception {
    LLMInvocationOptions options =
        JSON.readValue("{\"max_tokens\":10,\"temperature\":0.2,\"top_p\":0.9}", LLMInvocationOptions.class);

    assertEquals(10, options.maxTokens());
    assertEquals(0.9, options.topP());
    assertEquals(Map.of("temperature", 0.2), options.extras());
    assertEquals(Map.of("max_tokens", 10, "top_p", 0.9, "temperature", 0.2), options.toModelKwargs());
  }

  @Test
  void responseTakesUsageFromTopLevelKey() throws Exception {
    LLMResponse response =
        LLMResponse.fromRaw(tree("{\"content\":\"hi\",\"usage\":{\"input_tokens\":2,\"output_tokens\":3}}"));

    assertEquals("hi", response.content());
    assertEquals(5, response.usage().totalTokens());
  }

  @Test
  void responseTakesUsageFromMetadata() throws Exception {
    LLMResponse nested =
        LLMResponse.fromRaw(tree("{\"content\":\"hi\",\"metadata\":{\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":1}}}"));
    LLMResponse flat =
        LLMResponse.fromRaw(tree("{\"content\":\"hi\",\"metadata\":{\"input_tokens\":2,\"output_tokens\":1,\"model\":\"m\"}}"));

    assertEquals(3, nested.usage().totalTokens());
    assertEquals(3, flat.usage().totalTokens());
    assertEquals("m", flat.usage().model());
  }

  @Test
  void responseDefaultsMissingFields() throws Exception {
    LLMResponse response = LLMResponse.fromRaw(tree("{}"));

    assertEquals("", response.content());
    assertTrue(response.metadata().isEmpty());
    assertNull(response.usage());
    assertNull(response.toolCalls());
  }

  @Test
  void responseReadsToolCallsInEitherCase() throws Exception {
    LLMResponse snake = LLMResponse.fromRaw(tree("{\"tool_calls\":[{\"id\":\"1\",\"name\":\"f\",\"args\":{}}]}"));
    LLMResponse camel = LLMResponse.fromRaw(tree("{\"toolCalls\":[{\"id\":\"1\",\"name\":\"f\",\"args\":{}}]}"));

    assertEquals(snake.toolCalls(), camel.toolCalls());
    assertEquals("f", snake.toolCalls().get(0).name());
  }

  @Test
  void streamEventNameRejectsLineBreaksAndNul() throws Exception {
    JsonNode data = tree("1");

    for (String bad : List.of("a\nb", "a\rb", "a\0b")) {
      assertThrows(IllegalArgumentException.class, () -> new StreamEvent(data, bad));
    }
    assertEquals("progress", new StreamEvent(data, "progress").event());
  }

  @Test
  void agentInputRequiresPayloadButAllowsJsonNull() throws Exception {
    assertThrows(Exception.class, () -> JSON.readValue("{}", AgentInput.class));
    assertTrue(JSON.readValue("{\"payload\":null}", AgentInput.class).payload().isNull());
  }

  @Test
  void toolDefinitionDefaults() {
    ToolDefinition tool = ToolDefinition.builder("echo", "Echoes", Map.of(), args -> args).build();

    assertTrue(tool.remote());
    assertEquals(30, tool.timeoutSeconds());
    assertTrue(tool.scopes().isEmpty());
    assertTrue(tool.network().isEmpty());
    assertTrue(tool.redactFields().isEmpty());
  }

  @Test
  void toolDefinitionSerializesWithoutCallable() throws Exception {
    ToolDefinition tool =
        ToolDefinition.builder("echo", "Echoes", Map.of("type", "object"), args -> args)
            .timeoutSeconds(5)
            .build();

    JsonNode wire = JSON.valueToTree(tool);

    assertTrue(wire.path("callable").isMissingNode());
    assertEquals(5, wire.path("timeout_seconds").asInt());
    assertEquals("object", wire.path("args_schema").path("type").asText());
  }

  @Test
  void eventParsesTimestampWithAndWithoutOffset() throws Exception {
    String template =
        "{\"event_id\":\"e\",\"session_id\":\"s\",\"actor_id\":\"a\",\"payload\":{\"k\":1},"
            + "\"branch\":{\"name\":\"b\",\"root_event_id\":\"r\"},\"timestamp\":\"%s\"}";

    Event withOffset = JSON.readValue(template.formatted("2026-01-02T03:04:05Z"), Event.class);
    Event noOffset = JSON.readValue(template.formatted("2026-01-02T03:04:05"), Event.class);

    assertEquals(Instant.parse("2026-01-02T03:04:05Z"), withOffset.timestamp());
    assertEquals(withOffset.timestamp(), noOffset.timestamp());
    assertEquals(new BranchRef("b", "r"), withOffset.branch());
    assertEquals(tree("{\"k\":1}"), withOffset.payload());
  }

  @Test
  void eventRejectsUnparseableTimestamp() {
    String wire = "{\"event_id\":\"e\",\"session_id\":\"s\",\"actor_id\":\"a\",\"payload\":1,\"timestamp\":\"yesterday\"}";

    assertThrows(Exception.class, () -> JSON.readValue(wire, Event.class));
  }

  @Test
  void sessionMessageDefaultsName() throws Exception {
    SessionMessage message =
        JSON.readValue(
            "{\"id\":\"1\",\"role\":\"user\",\"content\":\"hi\",\"timestamp\":\"\",\"session_id\":\"s\"}",
            SessionMessage.class);

    assertEquals("", message.name());
    assertNull(message.toolCalls());
  }

  @Test
  void sessionSummaryDefaultsPreview() throws Exception {
    SessionSummary summary =
        JSON.readValue(
            "{\"session_id\":\"s\",\"last_activity\":\"\",\"created_at\":\"\",\"message_count\":3}",
            SessionSummary.class);

    assertEquals("", summary.firstMessagePreview());
    assertEquals(3, summary.messageCount());
  }
}
