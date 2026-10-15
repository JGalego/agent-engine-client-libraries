package com.mongodb.agentengine.sdk.langchain4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.agentengine.sdk.Json;
import com.mongodb.agentengine.sdk.LLMResponse;
import com.mongodb.agentengine.sdk.LLMStreamChunk;
import com.mongodb.agentengine.sdk.LLMToolSchema;
import com.mongodb.agentengine.sdk.Message;
import com.mongodb.agentengine.sdk.MessageContent;
import com.mongodb.agentengine.sdk.Role;
import com.mongodb.agentengine.sdk.ToolCallChunk;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Exercises {@link LangChain4jLLM} against the real OpenAI API with a few short prompts. It runs
 * only when {@code OPENAI_API_KEY} is set; {@code OPENAI_MODEL} overrides the default model.
 */
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
class LangChain4jLiveTest {

  private static final String MODEL = System.getenv().getOrDefault("OPENAI_MODEL", "gpt-4o-mini");
  private static final Map<String, Object> SHORT = Map.of("max_tokens", 60, "temperature", 0.0);

  private static LangChain4jLLM.Builder llm() {
    String key = System.getenv("OPENAI_API_KEY");
    return LangChain4jLLM.builder()
        .chatModel(OpenAiChatModel.builder().apiKey(key).modelName(MODEL).build())
        .streamingModel(OpenAiStreamingChatModel.builder().apiKey(key).modelName(MODEL).build());
  }

  private static LLMToolSchema weatherTool() throws Exception {
    JsonNode parameters =
        Json.mapper()
            .readTree(
                "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\",\"description\":\"City name\"}},\"required\":[\"city\"]}");
    return new LLMToolSchema("get_weather", "Gets the current weather for a city", parameters, null, null, null);
  }

  @Test
  void invokeReturnsTextAndUsage() {
    LLMResponse response = llm().build().invoke(List.of(Message.user("Reply with exactly the word: pong")), SHORT);

    assertTrue(response.content().toLowerCase().contains("pong"), response.content());
    assertNotNull(response.usage());
    assertTrue(response.usage().inputTokens() > 0);
    assertTrue(response.usage().totalTokens() >= response.usage().inputTokens());
    assertNotNull(response.id());
    assertEquals("stop", response.responseMetadata().get("finish_reason").asText());
  }

  @Test
  void streamDeliversTextAndFinalUsage() {
    List<LLMStreamChunk> chunks = llm().build().stream(List.of(Message.user("Count from 1 to 5, separated by spaces.")), SHORT).toList();

    String text = chunks.stream().map(LLMStreamChunk::content).filter(c -> c != null).reduce("", String::concat);
    assertTrue(text.contains("1") && text.contains("5"), text);
    assertTrue(chunks.stream().filter(c -> c.content() != null).count() > 1, "expected incremental chunks");
    assertNotNull(chunks.get(chunks.size() - 1).usage(), "expected usage on the final chunk");
  }

  @Test
  void invokeCallsABoundTool() throws Exception {
    LLMResponse response =
        llm().tools(List.of(weatherTool())).toolChoice(ToolChoice.REQUIRED).build()
            .invoke(List.of(Message.user("What is the weather in Paris?")), SHORT);

    assertNotNull(response.toolCalls());
    var call = response.toolCalls().get(0);
    assertEquals("get_weather", call.name());
    assertEquals("Paris", call.args().get("city").asText());
    assertNotNull(call.id());
  }

  @Test
  void streamAssemblesAToolCallFromFragments() throws Exception {
    List<LLMStreamChunk> chunks =
        llm().tools(List.of(weatherTool())).toolChoice(ToolChoice.REQUIRED).build()
            .stream(List.of(Message.user("What is the weather in Paris?")), SHORT).toList();

    List<ToolCallChunk> parts = chunks.stream().filter(c -> c.toolCalls() != null).flatMap(c -> c.toolCalls().stream()).toList();
    assertFalse(parts.isEmpty());
    assertEquals("get_weather", parts.get(0).name());
    String arguments = parts.stream().map(ToolCallChunk::args).filter(a -> a != null).reduce("", String::concat);
    assertEquals("Paris", Json.mapper().readTree(arguments).get("city").asText());
  }

  @Test
  void aToolResultFlowsBackToTheModel() throws Exception {
    LangChain4jLLM llm = llm().tools(List.of(weatherTool())).build();
    List<Message> conversation = new ArrayList<>(List.of(Message.user("What is the weather in Paris? Answer in one short sentence.")));

    LLMResponse first = llm.invoke(conversation, SHORT);
    conversation.add(new Message(Role.ASSISTANT, new MessageContent.Text(first.content()), first.toolCalls(), null, null, null, null, null, null));
    conversation.add(new Message(Role.TOOL, new MessageContent.Text("17 degrees and sunny"), null, first.toolCalls().get(0).id(), false, "get_weather", null, null, null));
    LLMResponse answer = llm.invoke(conversation, SHORT);

    assertTrue(answer.content().contains("17"), answer.content());
    assertNull(answer.toolCalls());
  }

  @Test
  void closingAStreamEarlyStopsCleanly() {
    try (Stream<LLMStreamChunk> stream = llm().build().stream(List.of(Message.user("Write a long story about a dragon.")), Map.of("max_tokens", 200))) {
      assertNotNull(stream.iterator().next());
    }
  }
}
