package com.mongodb.agentengine.sdk.langchain4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.mongodb.agentengine.sdk.BaseLLM;
import com.mongodb.agentengine.sdk.Json;
import com.mongodb.agentengine.sdk.LLMResponse;
import com.mongodb.agentengine.sdk.LLMStreamChunk;
import com.mongodb.agentengine.sdk.LLMTokenUsage;
import com.mongodb.agentengine.sdk.LLMToolCall;
import com.mongodb.agentengine.sdk.LLMToolSchema;
import com.mongodb.agentengine.sdk.Message;
import com.mongodb.agentengine.sdk.ToolCallChunk;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.FinishReason;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class BaseLlmChatModelTest {

  /** Records its inputs and answers with canned chunks. */
  private static final class RecordingLlm implements BaseLLM {
    List<Message> messages;
    Map<String, Object> kwargs;
    List<LLMStreamChunk> chunks = List.of();
    LLMResponse response = new LLMResponse("ok", null, null, null, null, null, null, null);
    RuntimeException failure;

    @Override
    public LLMResponse invoke(List<Message> messages, Map<String, Object> kwargs) {
      this.messages = messages;
      this.kwargs = kwargs;
      if (failure != null) {
        throw failure;
      }
      return response;
    }

    @Override
    public Stream<LLMStreamChunk> stream(List<Message> messages, Map<String, Object> kwargs) {
      this.messages = messages;
      this.kwargs = kwargs;
      if (failure != null) {
        throw failure;
      }
      return chunks.stream();
    }
  }

  private static ChatRequest request(String text) {
    return ChatRequest.builder().messages(UserMessage.from(text)).build();
  }

  private static JsonNode json(String text) throws Exception {
    return Json.mapper().readTree(text);
  }

  // --- blocking -------------------------------------------------------------------------------

  @Test
  void aRequestBecomesPlatformMessagesAndKeywordArguments() {
    var llm = new RecordingLlm();
    var request =
        ChatRequest.builder()
            .messages(UserMessage.from("hi"))
            .temperature(0.2)
            .topP(0.9)
            .maxOutputTokens(100)
            .stopSequences(List.of("END"))
            .modelName("m")
            .build();

    new BaseLlmChatModel(llm).chat(request);

    assertEquals(1, llm.messages.size());
    assertEquals("hi", ((com.mongodb.agentengine.sdk.MessageContent.Text) llm.messages.get(0).content()).value());
    assertEquals(0.2, llm.kwargs.get("temperature"));
    assertEquals(0.9, llm.kwargs.get("top_p"));
    assertEquals(100, llm.kwargs.get("max_tokens"));
    assertEquals(List.of("END"), llm.kwargs.get("stop"));
    assertEquals("m", llm.kwargs.get("model"));
    assertTrue(!llm.kwargs.containsKey("tools"));
  }

  @Test
  void toolsAndTheirChoiceTravelAsReservedKeys() throws Exception {
    var llm = new RecordingLlm();
    ToolSpecification spec =
        ToolSpecification.builder().name("lookup").description("Looks up").parameters(JsonObjectSchema.builder().addStringProperty("q").required("q").build()).build();

    new BaseLlmChatModel(llm).chat(ChatRequest.builder().messages(UserMessage.from("hi")).toolSpecifications(spec).toolChoice(ToolChoice.REQUIRED).build());

    @SuppressWarnings("unchecked")
    var tools = (List<LLMToolSchema>) llm.kwargs.get("tools");
    assertEquals("lookup", tools.get(0).name());
    assertEquals(json("{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"]}"), tools.get(0).parameters());
    assertEquals("required", llm.kwargs.get("tool_choice"));
  }

  @Test
  void aTextResponseMapsToAnAiMessageWithUsage() {
    var llm = new RecordingLlm();
    llm.response = new LLMResponse("Hello", null, null, new LLMTokenUsage(3, 4, null, null, null, null, "gpt-x"), "resp_1", null, null, null);

    ChatResponse response = new BaseLlmChatModel(llm).chat(request("hi"));

    assertEquals("Hello", response.aiMessage().text());
    assertEquals("resp_1", response.id());
    assertEquals("gpt-x", response.modelName());
    assertEquals(7, response.tokenUsage().totalTokenCount());
    assertEquals(FinishReason.STOP, response.finishReason());
    assertTrue(!response.aiMessage().hasToolExecutionRequests());
  }

  @Test
  void toolCallsMapToExecutionRequestsAndTheToolFinishReason() throws Exception {
    var llm = new RecordingLlm();
    llm.response = new LLMResponse("", List.of(new LLMToolCall("c1", "lookup", json("{\"q\":\"x\"}"), "tool_call", 0)), null, null, null, null, null, null);

    ChatResponse response = new BaseLlmChatModel(llm).chat(request("hi"));

    assertTrue(response.aiMessage().hasToolExecutionRequests());
    assertEquals("c1", response.aiMessage().toolExecutionRequests().get(0).id());
    assertEquals("{\"q\":\"x\"}", response.aiMessage().toolExecutionRequests().get(0).arguments());
    assertNull(response.aiMessage().text());
    assertEquals(FinishReason.TOOL_EXECUTION, response.finishReason());
  }

  @Test
  void aReportedFinishReasonIsKept() {
    var llm = new RecordingLlm();
    llm.response = new LLMResponse("cut", null, Map.of(), null, null, null, null, Map.of("finish_reason", JsonNodeFactory.instance.textNode("length")));
    var other = new RecordingLlm();
    other.response = new LLMResponse("x", null, Map.of(), null, null, null, null, Map.of("finish_reason", JsonNodeFactory.instance.textNode("mystery")));

    assertEquals(FinishReason.LENGTH, new BaseLlmChatModel(llm).chat(request("hi")).finishReason());
    assertEquals(FinishReason.OTHER, new BaseLlmChatModel(other).chat(request("hi")).finishReason());
  }

  @Test
  void aProviderFailurePropagates() {
    var llm = new RecordingLlm();
    llm.failure = new IllegalStateException("down");

    var error = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> new BaseLlmChatModel(llm).chat(request("hi")));

    assertEquals("down", error.getMessage());
  }

  // --- streaming ------------------------------------------------------------------------------

  /** Collects what a streaming model tells its handler. */
  private static final class Collector implements StreamingChatResponseHandler {
    final List<String> partials = new ArrayList<>();
    ChatResponse complete;
    Throwable error;

    @Override
    public void onPartialResponse(String partial) {
      partials.add(partial);
    }

    @Override
    public void onCompleteResponse(ChatResponse response) {
      complete = response;
    }

    @Override
    public void onError(Throwable error) {
      this.error = error;
    }
  }

  @Test
  void streamingDeliversTextThenTheCompleteResponse() {
    var llm = new RecordingLlm();
    llm.chunks =
        List.of(
            new LLMStreamChunk("Hel", null, null, null, null, null, null),
            new LLMStreamChunk("lo", null, null, null, null, null, null),
            new LLMStreamChunk(null, null, new LLMTokenUsage(1, 2, null, null, null, null, null), "r1", null, null, null));
    var collector = new Collector();

    new BaseLlmStreamingChatModel(llm).chat(request("hi"), collector);

    assertEquals(List.of("Hel", "lo"), collector.partials);
    assertEquals("Hello", collector.complete.aiMessage().text());
    assertEquals("r1", collector.complete.id());
    assertEquals(3, collector.complete.tokenUsage().totalTokenCount());
  }

  @Test
  void streamedToolCallsArriveWithTheCompleteResponse() {
    var llm = new RecordingLlm();
    llm.chunks =
        List.of(
            new LLMStreamChunk(null, List.of(new ToolCallChunk("c1", "lookup", "{\"q\":", null, 0)), null, null, null, null, null),
            new LLMStreamChunk(null, List.of(new ToolCallChunk(null, null, "\"x\"}", null, 0)), null, null, null, null, null));
    var collector = new Collector();

    new BaseLlmStreamingChatModel(llm).chat(request("hi"), collector);

    assertTrue(collector.partials.isEmpty());
    assertEquals("{\"q\":\"x\"}", collector.complete.aiMessage().toolExecutionRequests().get(0).arguments());
    assertEquals(FinishReason.TOOL_EXECUTION, collector.complete.finishReason());
  }

  @Test
  void aStreamFailureReachesTheHandlerAndNothingCompletes() {
    var llm = new RecordingLlm();
    llm.failure = new IllegalStateException("stream broke");
    var collector = new Collector();

    new BaseLlmStreamingChatModel(llm).chat(request("hi"), collector);

    assertEquals("stream broke", collector.error.getMessage());
    assertNull(collector.complete);
  }
}
