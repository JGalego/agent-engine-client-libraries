package com.mongodb.agentengine.sdk.langchain4j;

import com.mongodb.agentengine.sdk.LLMResponse;
import com.mongodb.agentengine.sdk.LLMToolSchema;
import com.mongodb.agentengine.sdk.LLMTokenUsage;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The request and response conversions shared by the chat-model adapters of a {@code BaseLLM}. */
final class ChatModelConversion {

  private ChatModelConversion() {}

  static Map<String, Object> kwargs(ChatRequest request) {
    Map<String, Object> kwargs = new LinkedHashMap<>();
    put(kwargs, "temperature", request.temperature());
    put(kwargs, "top_p", request.topP());
    put(kwargs, "top_k", request.topK());
    put(kwargs, "frequency_penalty", request.frequencyPenalty());
    put(kwargs, "presence_penalty", request.presencePenalty());
    put(kwargs, "max_tokens", request.maxOutputTokens());
    put(kwargs, "model", request.modelName());
    if (request.stopSequences() != null && !request.stopSequences().isEmpty()) {
      kwargs.put("stop", request.stopSequences());
    }
    List<ToolSpecification> specifications = request.toolSpecifications();
    if (specifications != null && !specifications.isEmpty()) {
      List<LLMToolSchema> tools = specifications.stream().map(ToolSchemas::toToolSchema).toList();
      kwargs.put("tools", tools);
      if (request.toolChoice() != null) {
        kwargs.put("tool_choice", request.toolChoice().name().toLowerCase(Locale.ROOT));
      }
    }
    return kwargs;
  }

  private static void put(Map<String, Object> kwargs, String key, Object value) {
    if (value != null) {
      kwargs.put(key, value);
    }
  }

  static ChatResponse toChatResponse(LLMResponse response) {
    boolean calls = response.toolCalls() != null && !response.toolCalls().isEmpty();
    String text = response.content().isEmpty() ? null : response.content();
    AiMessage message = calls ? AiMessage.from(text, Messages.toolExecutionRequests(response.toolCalls())) : AiMessage.from(text == null ? "" : text);

    ChatResponse.Builder builder = ChatResponse.builder().aiMessage(message).id(response.id()).finishReason(finishReason(response, calls));
    LLMTokenUsage usage = response.usage();
    if (usage != null) {
      builder.tokenUsage(new TokenUsage(usage.inputTokens(), usage.outputTokens(), usage.totalTokens()));
      builder.modelName(usage.model());
    }
    return builder.build();
  }

  private static FinishReason finishReason(LLMResponse response, boolean calls) {
    if (calls) {
      return FinishReason.TOOL_EXECUTION;
    }
    var reported = response.responseMetadata() == null ? null : response.responseMetadata().get("finish_reason");
    if (reported != null && reported.isTextual()) {
      try {
        return FinishReason.valueOf(reported.asText().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException unknown) {
        return FinishReason.OTHER;
      }
    }
    return FinishReason.STOP;
  }
}
