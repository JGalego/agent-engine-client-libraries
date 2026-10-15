package com.mongodb.agentengine.sdk.langchain4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.agentengine.sdk.Json;
import com.mongodb.agentengine.sdk.LLMResponse;
import com.mongodb.agentengine.sdk.LLMStreamChunk;
import com.mongodb.agentengine.sdk.LLMToolSchema;
import com.mongodb.agentengine.sdk.Message;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.chat.response.PartialToolCallContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class LangChain4jLLMTest {

  /** Records the request and answers with a canned response. */
  private static final class FakeChatModel implements ChatModel {
    ChatRequest last;
    final Function<ChatRequest, ChatResponse> reply;

    FakeChatModel(Function<ChatRequest, ChatResponse> reply) {
      this.reply = reply;
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
      last = request;
      return reply.apply(request);
    }
  }

  /** Plays a script of handler calls on its own thread, like a real provider client. */
  private static class FakeStreamingModel implements StreamingChatModel {
    ChatRequest last;
    final AtomicBoolean cancelled = new AtomicBoolean();
    final CountDownLatch finished = new CountDownLatch(1);
    final Consumer<StreamingChatResponseHandler> script;

    FakeStreamingModel(Consumer<StreamingChatResponseHandler> script) {
      this.script = script;
    }

    @Override
    public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
      last = request;
      play(handler, () -> {
        script.accept(handler);
        finished.countDown();
      });
    }

    /** Runs a script on its own thread; a script that throws ends the stream with that error. */
    static void play(StreamingChatResponseHandler handler, Runnable script) {
      new Thread(() -> {
        try {
          script.run();
        } catch (Throwable t) {
          handler.onError(t);
        }
      }).start();
    }

    StreamingHandle handle() {
      return new StreamingHandle() {
        @Override
        public void cancel() {
          cancelled.set(true);
        }

        @Override
        public boolean isCancelled() {
          return cancelled.get();
        }
      };
    }
  }

  private static ChatResponse answer(String text) {
    return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
  }

  private static final List<Message> HELLO = List.of(Message.user("hi"));

  // --- invoke -----------------------------------------------------------------------------

  @Test
  void invokeConvertsMessagesAndResponse() {
    var model =
        new FakeChatModel(
            r ->
                ChatResponse.builder()
                    .aiMessage(AiMessage.from("hello"))
                    .id("resp_1")
                    .modelName("m-1")
                    .tokenUsage(new TokenUsage(3, 4))
                    .finishReason(FinishReason.STOP)
                    .build());

    LLMResponse response = LangChain4jLLM.of(model).invoke(List.of(Message.system("terse"), Message.user("hi")));

    assertEquals(2, model.last.messages().size());
    assertEquals("hello", response.content());
    assertEquals("resp_1", response.id());
    assertEquals(3, response.usage().inputTokens());
    assertEquals(4, response.usage().outputTokens());
    assertEquals(7, response.usage().totalTokens());
    assertEquals("m-1", response.usage().model());
    assertEquals("stop", response.responseMetadata().get("finish_reason").asText());
    assertNull(response.toolCalls());
  }

  @Test
  void invokeReturnsToolCalls() throws Exception {
    var request = ToolExecutionRequest.builder().id("c1").name("lookup").arguments("{\"q\":\"x\"}").build();
    var model = new FakeChatModel(r -> ChatResponse.builder().aiMessage(AiMessage.from(request)).finishReason(FinishReason.TOOL_EXECUTION).build());

    LLMResponse response = LangChain4jLLM.of(model).invoke(HELLO);

    assertEquals("", response.content());
    assertEquals("lookup", response.toolCalls().get(0).name());
    assertEquals(Json.mapper().readTree("{\"q\":\"x\"}"), response.toolCalls().get(0).args());
    assertNull(response.usage());
  }

  @Test
  void kwargsBecomeRequestParameters() {
    var model = new FakeChatModel(r -> answer("ok"));
    Map<String, Object> kwargs =
        Map.of(
            "temperature", 0.2,
            "top_p", 0.9,
            "top_k", 40,
            "frequency_penalty", 0.1,
            "presence_penalty", 0.3,
            "max_tokens", 256,
            "stop", List.of("END"),
            "model", "other-model",
            "seed", 7);

    LangChain4jLLM.of(model).invoke(HELLO, kwargs);

    var sent = model.last;
    assertEquals(0.2, sent.temperature());
    assertEquals(0.9, sent.topP());
    assertEquals(40, sent.topK());
    assertEquals(0.1, sent.frequencyPenalty());
    assertEquals(0.3, sent.presencePenalty());
    assertEquals(256, sent.maxOutputTokens());
    assertEquals(List.of("END"), sent.stopSequences());
    assertEquals("other-model", sent.modelName());
  }

  @Test
  void aSingleStopStringIsAccepted() {
    var model = new FakeChatModel(r -> answer("ok"));

    LangChain4jLLM.of(model).invoke(HELLO, Map.of("stop", "END"));

    assertEquals(List.of("END"), model.last.stopSequences());
  }

  @Test
  void toolsAreBoundToEveryRequest() throws Exception {
    JsonNode params = Json.mapper().readTree("{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}},\"required\":[\"q\"]}");
    var model = new FakeChatModel(r -> answer("ok"));
    var llm =
        LangChain4jLLM.builder()
            .chatModel(model)
            .tools(List.of(new LLMToolSchema("search", "Searches", params, null, null, null)))
            .toolChoice(ToolChoice.REQUIRED)
            .build();

    llm.invoke(HELLO);

    assertEquals("search", model.last.toolSpecifications().get(0).name());
    assertEquals(ToolChoice.REQUIRED, model.last.toolChoice());
  }

  @Test
  void noToolsMeansNoToolSpecifications() {
    var model = new FakeChatModel(r -> answer("ok"));

    LangChain4jLLM.of(model).invoke(HELLO);

    assertTrue(model.last.toolSpecifications() == null || model.last.toolSpecifications().isEmpty());
  }

  @Test
  void invokeNeedsAChatModel() {
    var llm = LangChain4jLLM.builder().streamingModel(new FakeStreamingModel(h -> {})).build();

    assertThrows(UnsupportedOperationException.class, () -> llm.invoke(HELLO));
  }

  @Test
  void aModelIsRequired() {
    assertThrows(IllegalStateException.class, () -> LangChain4jLLM.builder().build());
  }

  // --- stream -----------------------------------------------------------------------------

  private static LangChain4jLLM streaming(FakeStreamingModel model) {
    return LangChain4jLLM.builder().streamingModel(model).build();
  }

  @Test
  void streamEmitsTextThenUsage() {
    var model = new FakeStreamingModel(null) {
      @Override
      public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
        play(handler, () -> {
          handler.onPartialResponse(new PartialResponse("Hel"), new PartialResponseContext(handle()));
          handler.onPartialResponse(new PartialResponse("lo"), new PartialResponseContext(handle()));
          handler.onCompleteResponse(
              ChatResponse.builder().aiMessage(AiMessage.from("Hello")).id("r1").tokenUsage(new TokenUsage(2, 5)).finishReason(FinishReason.STOP).modelName("m").build());
        });
      }
    };

    List<LLMStreamChunk> chunks = streaming(model).stream(HELLO).toList();

    assertEquals(List.of("Hel", "lo"), chunks.stream().map(LLMStreamChunk::content).filter(c -> c != null).toList());
    LLMStreamChunk last = chunks.get(chunks.size() - 1);
    assertNull(last.content());
    assertEquals(7, last.usage().totalTokens());
    assertEquals("r1", last.id());
  }

  @Test
  void streamEmitsPartialToolCallsAsTheyArrive() {
    var model = new FakeStreamingModel(null) {
      @Override
      public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
        play(handler, () -> {
          var ctx = new PartialToolCallContext(handle());
          handler.onPartialToolCall(PartialToolCall.builder().index(0).id("c1").name("lookup").partialArguments("{\"q\":").build(), ctx);
          handler.onPartialToolCall(PartialToolCall.builder().index(0).id("c1").name("lookup").partialArguments("\"x\"}").build(), ctx);
          var request1 = ToolExecutionRequest.builder().id("c1").name("lookup").arguments("{\"q\":\"x\"}").build();
          handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from(request1)).build());
        });
      }
    };

    List<LLMStreamChunk> chunks = streaming(model).stream(HELLO).toList();

    var calls = chunks.stream().filter(c -> c.toolCalls() != null).flatMap(c -> c.toolCalls().stream()).toList();
    assertEquals(2, calls.size(), "complete calls must not be emitted again");
    assertEquals("lookup", calls.get(0).name());
    assertEquals("{\"q\":", calls.get(0).args());
    assertEquals("\"x\"}", calls.get(1).args());
    assertEquals(0, calls.get(1).index());
  }

  @Test
  void streamEmitsCompleteToolCallsWhenNoPartialsArrive() {
    var request = ToolExecutionRequest.builder().id("c1").name("lookup").arguments("{}").build();
    var model = new FakeStreamingModel(h -> h.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from(request)).build()));

    List<LLMStreamChunk> chunks = streaming(model).stream(HELLO).toList();

    var calls = chunks.stream().filter(c -> c.toolCalls() != null).flatMap(c -> c.toolCalls().stream()).toList();
    assertEquals(1, calls.size());
    assertEquals("c1", calls.get(0).id());
    assertEquals("tool_call", calls.get(0).type());
  }

  @Test
  void streamErrorsSurfaceToTheConsumer() {
    var model = new FakeStreamingModel(h -> h.onError(new IllegalStateException("provider down")));

    Stream<LLMStreamChunk> stream = streaming(model).stream(HELLO);

    var error = assertThrows(IllegalStateException.class, stream::toList);
    assertEquals("provider down", error.getMessage());
  }

  @Test
  void checkedStreamErrorsAreWrapped() {
    var model = new FakeStreamingModel(h -> h.onError(new java.io.IOException("reset")));

    var error = assertThrows(IllegalStateException.class, () -> streaming(model).stream(HELLO).toList());

    assertTrue(error.getCause() instanceof java.io.IOException);
  }

  @Test
  void closingTheStreamCancelsTheModelCall() throws Exception {
    CountDownLatch firstChunkSent = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    var model = new FakeStreamingModel(null) {
      @Override
      public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
        play(handler, () -> {
          handler.onPartialResponse(new PartialResponse("one"), new PartialResponseContext(handle()));
          firstChunkSent.countDown();
          try {
            release.await(5, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          handler.onPartialResponse(new PartialResponse("two"), new PartialResponseContext(handle()));
          finished.countDown();
        });
      }
    };

    try (Stream<LLMStreamChunk> stream = streaming(model).stream(HELLO)) {
      var first = stream.iterator().next();
      assertEquals("one", first.content());
      assertTrue(firstChunkSent.await(5, TimeUnit.SECONDS));
    }
    assertTrue(model.cancelled.get());
    release.countDown();
    assertTrue(model.finished.await(5, TimeUnit.SECONDS));
  }

  @Test
  void streamNeedsAStreamingModel() {
    var llm = LangChain4jLLM.of(new FakeChatModel(r -> answer("ok")));

    assertThrows(UnsupportedOperationException.class, () -> llm.stream(HELLO));
  }

  @Test
  void streamSendsTheConvertedRequest() {
    var model = new FakeStreamingModel(h -> h.onCompleteResponse(answer("ok")));

    streaming(model).stream(List.of(Message.user("hi")), Map.of("temperature", 0.5)).toList();

    assertEquals(0.5, model.last.temperature());
    assertEquals("hi", ((UserMessage) model.last.messages().get(0)).singleText());
    assertFalse(model.last.messages().isEmpty());
  }
}
