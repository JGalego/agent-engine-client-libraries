package com.mongodb.agentengine.runner;

import com.mongodb.agentengine.sdk.BaseLLM;
import com.mongodb.agentengine.sdk.LLMResponse;
import com.mongodb.agentengine.sdk.LLMStreamChunk;
import com.mongodb.agentengine.sdk.Message;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** A provider that answers every call with fixed chunks, and records what it was asked. */
class StubLlm implements BaseLLM {

  List<Message> lastMessages;
  Map<String, Object> lastKwargs;
  List<LLMStreamChunk> chunks = List.of(new LLMStreamChunk("stub", null, null, null, null, null, null));
  RuntimeException failure;

  @Override
  public LLMResponse invoke(List<Message> messages, Map<String, Object> kwargs) {
    return LLMResponse.fromChunks(stream(messages, kwargs).toList());
  }

  @Override
  public Stream<LLMStreamChunk> stream(List<Message> messages, Map<String, Object> kwargs) {
    lastMessages = messages;
    lastKwargs = kwargs;
    if (failure != null) {
      throw failure;
    }
    return chunks.stream();
  }
}
