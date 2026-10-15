package com.mongodb.agentengine.sdk.langchain4j;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.mongodb.agentengine.sdk.BaseLLM;
import com.mongodb.agentengine.sdk.LLMResponse;
import com.mongodb.agentengine.sdk.LLMStreamChunk;
import com.mongodb.agentengine.sdk.LLMTokenUsage;
import com.mongodb.agentengine.sdk.LLMToolSchema;
import com.mongodb.agentengine.sdk.Message;
import com.mongodb.agentengine.sdk.ToolCallChunk;
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
import dev.langchain4j.model.output.TokenUsage;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * A LangChain4j chat model exposed as the framework-neutral {@link BaseLLM}.
 *
 * <p>The tool pod calls LLMs through {@code BaseLLM} while the provider underneath is a LangChain4j
 * model. {@link #invoke} needs a {@link ChatModel} and {@link #stream} a {@link
 * StreamingChatModel}; the provider classes of most LangChain4j integrations implement both.
 *
 * <p>The {@code kwargs} of a call map onto LangChain4j request parameters: {@code temperature},
 * {@code top_p}, {@code top_k}, {@code frequency_penalty}, {@code presence_penalty}, {@code
 * max_tokens}, {@code stop} and {@code model}. LangChain4j has no request parameter for other
 * options, so they are ignored.
 */
public final class LangChain4jLLM implements BaseLLM {

  private final ChatModel chatModel;
  private final StreamingChatModel streamingModel;
  private final List<LLMToolSchema> tools;
  private final ToolChoice toolChoice;

  private LangChain4jLLM(Builder b) {
    this.chatModel = b.chatModel;
    this.streamingModel = b.streamingModel;
    this.tools = List.copyOf(b.tools);
    this.toolChoice = b.toolChoice;
  }

  /** Starts an adapter. Set at least one of {@link Builder#chatModel} and {@link Builder#streamingModel}. */
  public static Builder builder() {
    return new Builder();
  }

  /** Wraps a model for {@link #invoke} only. */
  public static LangChain4jLLM of(ChatModel chatModel) {
    return builder().chatModel(chatModel).build();
  }

  /** Builder for {@link LangChain4jLLM}. */
  public static final class Builder {
    private ChatModel chatModel;
    private StreamingChatModel streamingModel;
    private List<LLMToolSchema> tools = List.of();
    private ToolChoice toolChoice;

    private Builder() {}

    /** The model behind {@link LangChain4jLLM#invoke}. */
    public Builder chatModel(ChatModel v) { chatModel = v; return this; }

    /** The model behind {@link LangChain4jLLM#stream}. */
    public Builder streamingModel(StreamingChatModel v) { streamingModel = v; return this; }

    /** Tools offered to the model on every call. */
    public Builder tools(List<LLMToolSchema> v) { tools = v; return this; }

    /** Whether the model may, must, or must not call a tool. Only meaningful with tools. */
    public Builder toolChoice(ToolChoice v) { toolChoice = v; return this; }

    /** @throws IllegalStateException if neither model is set */
    public LangChain4jLLM build() {
      if (chatModel == null && streamingModel == null) {
        throw new IllegalStateException("A chatModel or a streamingModel is required");
      }
      return new LangChain4jLLM(this);
    }
  }

  // ---------------------------------------------------------------------------
  // BaseLLM
  // ---------------------------------------------------------------------------

  @Override
  public LLMResponse invoke(List<Message> messages, Map<String, Object> kwargs) {
    if (chatModel == null) {
      throw new UnsupportedOperationException("No ChatModel was configured; only stream() is available");
    }
    return toLlmResponse(chatModel.chat(request(messages, kwargs)));
  }

  @Override
  public Stream<LLMStreamChunk> stream(List<Message> messages, Map<String, Object> kwargs) {
    if (streamingModel == null) {
      throw new UnsupportedOperationException("No StreamingChatModel was configured; only invoke() is available");
    }
    ChunkQueue queue = new ChunkQueue();
    streamingModel.chat(request(messages, kwargs), queue.handler());
    return StreamSupport.stream(queue.spliterator(), false).onClose(queue::cancel);
  }

  // ---------------------------------------------------------------------------
  // Request
  // ---------------------------------------------------------------------------

  private ChatRequest request(List<Message> messages, Map<String, Object> kwargs) {
    ChatRequest.Builder request = ChatRequest.builder().messages(Messages.toLangChain(messages));
    if (!tools.isEmpty()) {
      request.toolSpecifications(tools.stream().map(ToolSchemas::toToolSpecification).toList());
      request.toolChoice(toolChoice);
    }
    request.temperature(number(kwargs, "temperature", Number::doubleValue));
    request.topP(number(kwargs, "top_p", Number::doubleValue));
    request.topK(number(kwargs, "top_k", Number::intValue));
    request.frequencyPenalty(number(kwargs, "frequency_penalty", Number::doubleValue));
    request.presencePenalty(number(kwargs, "presence_penalty", Number::doubleValue));
    request.maxOutputTokens(number(kwargs, "max_tokens", Number::intValue));
    if (kwargs.get("model") instanceof String model) {
      request.modelName(model);
    }
    if (kwargs.get("stop") instanceof List<?> stop) {
      request.stopSequences(stop.stream().map(String::valueOf).toList());
    } else if (kwargs.get("stop") instanceof String stop) {
      request.stopSequences(List.of(stop));
    }
    return request.build();
  }

  private static <T> T number(Map<String, Object> kwargs, String key, Function<Number, T> convert) {
    return kwargs.get(key) instanceof Number n ? convert.apply(n) : null;
  }

  // ---------------------------------------------------------------------------
  // Response
  // ---------------------------------------------------------------------------

  private static LLMResponse toLlmResponse(ChatResponse response) {
    var ai = response.aiMessage();
    return new LLMResponse(
        ai.text(),
        ai.hasToolExecutionRequests() ? Messages.toolCalls(ai.toolExecutionRequests()) : null,
        null,
        toUsage(response.tokenUsage(), response.modelName()),
        response.id(),
        null,
        null,
        responseMetadata(response));
  }

  private static LLMTokenUsage toUsage(TokenUsage usage, String model) {
    if (usage == null) {
      return null;
    }
    return new LLMTokenUsage(
        usage.inputTokenCount(), usage.outputTokenCount(), null, null, usage.totalTokenCount(), null, model);
  }

  private static Map<String, JsonNode> responseMetadata(ChatResponse response) {
    Map<String, JsonNode> metadata = new LinkedHashMap<>();
    if (response.finishReason() != null) {
      metadata.put("finish_reason", JsonNodeFactory.instance.textNode(response.finishReason().name().toLowerCase()));
    }
    if (response.modelName() != null) {
      metadata.put("model_name", JsonNodeFactory.instance.textNode(response.modelName()));
    }
    return metadata.isEmpty() ? null : metadata;
  }

  // ---------------------------------------------------------------------------
  // Streaming bridge
  // ---------------------------------------------------------------------------

  /**
   * Turns LangChain4j's callbacks into a blocking iterator. The model pushes chunks from its own
   * thread, and the stream's consumer pulls them. Closing the stream cancels the model call.
   */
  private static final class ChunkQueue implements Iterable<LLMStreamChunk> {

    private static final Object END = new Object();

    private record Failure(Throwable error) {}

    private final BlockingQueue<Object> queue = new LinkedBlockingQueue<>();
    private volatile StreamingHandle handle;
    private volatile boolean cancelled;

    StreamingChatResponseHandler handler() {
      return new StreamingChatResponseHandler() {
        private boolean sawToolCallParts;

        @Override
        public void onPartialResponse(PartialResponse partial, PartialResponseContext context) {
          handle = context.streamingHandle();
          offer(new LLMStreamChunk(partial.text(), null, null, null, null, null, null));
        }

        @Override
        public void onPartialToolCall(PartialToolCall partial, PartialToolCallContext context) {
          handle = context.streamingHandle();
          sawToolCallParts = true;
          ToolCallChunk call = new ToolCallChunk(partial.id(), partial.name(), partial.partialArguments(), null, partial.index());
          offer(new LLMStreamChunk(null, List.of(call), null, null, null, null, null));
        }

        @Override
        public void onCompleteResponse(ChatResponse response) {
          var ai = response.aiMessage();
          // Models that stream text only deliver tool calls here, already complete.
          if (!sawToolCallParts && ai.hasToolExecutionRequests()) {
            List<ToolCallChunk> calls = new ArrayList<>();
            var requests = ai.toolExecutionRequests();
            for (int i = 0; i < requests.size(); i++) {
              var request = requests.get(i);
              calls.add(new ToolCallChunk(request.id(), request.name(), request.arguments(), "tool_call", i));
            }
            offer(new LLMStreamChunk(null, calls, null, null, null, null, null));
          }
          offer(new LLMStreamChunk(null, null, toUsage(response.tokenUsage(), response.modelName()), response.id(), null, null, responseMetadata(response)));
          finish(END);
        }

        @Override
        public void onError(Throwable error) {
          finish(new Failure(error));
        }
      };
    }

    private void offer(LLMStreamChunk chunk) {
      if (!cancelled) {
        queue.add(chunk);
      }
    }

    private void finish(Object terminator) {
      if (!cancelled) {
        queue.add(terminator);
      }
    }

    void cancel() {
      cancelled = true;
      StreamingHandle current = handle;
      if (current != null) {
        current.cancel();
      }
      queue.clear();
      queue.add(END);
    }

    @Override
    public Iterator<LLMStreamChunk> iterator() {
      return new Iterator<>() {
        private Object next;
        private boolean done;

        @Override
        public boolean hasNext() {
          if (next != null) {
            return true;
          }
          if (done) {
            return false;
          }
          try {
            Object item = queue.take();
            if (item == END) {
              done = true;
              return false;
            }
            if (item instanceof Failure failure) {
              done = true;
              throw failure.error() instanceof RuntimeException e ? e : new IllegalStateException(failure.error());
            }
            next = item;
            return true;
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            done = true;
            cancel();
            throw new IllegalStateException("Interrupted while waiting for the next chunk", e);
          }
        }

        @Override
        public LLMStreamChunk next() {
          if (!hasNext()) {
            throw new NoSuchElementException();
          }
          LLMStreamChunk chunk = (LLMStreamChunk) next;
          next = null;
          return chunk;
        }
      };
    }
  }

  @Override
  public String toString() {
    return "LangChain4jLLM[chat=" + (chatModel != null) + ", streaming=" + (streamingModel != null) + ", tools=" + tools.size() + "]";
  }
}
