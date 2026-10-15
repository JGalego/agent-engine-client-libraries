# agent-engine-sdk-langchain4j

[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

LangChain4j adapters for Atlas Agent Engine. They let a LangChain4j chat model serve as the
framework-neutral `BaseLLM` that the platform runtime calls, and they translate messages and tool
schemas between the two type systems.

> This is an internal adapter package. A LangChain4j agent SDK that builds on it is planned;
> until then it is for code that integrates LangChain4j with the core SDK directly.

## Install

```xml
<dependency>
  <groupId>com.mongodb.agentengine</groupId>
  <artifactId>agent-engine-sdk-langchain4j</artifactId>
  <version>0.11.9</version>
</dependency>
```

It depends on `langchain4j-core`. Add the provider module you use, such as
`langchain4j-open-ai` or `langchain4j-anthropic`.

## Use it

`LangChain4jLLM` wraps a `ChatModel` for `invoke` and a `StreamingChatModel` for `stream`. Most
provider classes implement both, so one model can serve both calls.

```java
OpenAiChatModel model = OpenAiChatModel.builder().apiKey(key).modelName("gpt-4o-mini").build();
OpenAiStreamingChatModel streaming = OpenAiStreamingChatModel.builder().apiKey(key).modelName("gpt-4o-mini").build();

BaseLLM llm = LangChain4jLLM.builder().chatModel(model).streamingModel(streaming).build();

LLMResponse response = llm.invoke(List.of(Message.user("What is 6x7?")));

try (Stream<LLMStreamChunk> chunks = llm.stream(List.of(Message.user("Tell me a story")))) {
  chunks.forEach(chunk -> System.out.print(chunk.content() == null ? "" : chunk.content()));
}
```

- Closing a stream cancels the underlying model call.
- A failure from the provider surfaces as an exception from the stream: the original one if it is
  unchecked, otherwise an `IllegalStateException` wrapping it.
- `kwargs` map onto LangChain4j request parameters: `temperature`, `top_p`, `top_k`,
  `frequency_penalty`, `presence_penalty`, `max_tokens`, `stop` and `model`. LangChain4j has no
  request parameter for other options, so they are ignored.
- To offer tools on every call, pass `LLMToolSchema`s to `.tools(...)` and, optionally, a
  `ToolChoice` to `.toolChoice(...)`.

### Using a platform model from LangChain4j

`BaseLlmChatModel` and `BaseLlmStreamingChatModel` are the inverse: they expose any `BaseLLM`, such as
the runtime's platform-routed `SecureLlm`, as a LangChain4j `ChatModel` or `StreamingChatModel`. Code
written for LangChain4j or LangGraph4j then runs on that model, and every call still goes through the
Orchestration Engine. A request's sampling parameters, stop sequences, tools and tool choice are passed
as the `kwargs` documented above.

### Messages and tools

`Messages` converts in both directions. A platform tool message without a tool call id is skipped
with a warning, because there is no call for it to answer. LangChain4j models PDFs but no other
document type, so other documents are sent as text.

`ToolSchemas` converts JSON Schema to `ToolSpecification` and back. It covers objects, arrays,
scalars, enums, `anyOf`/`oneOf`, type unions and `$ref` with `$defs`. A subschema outside that
vocabulary is forwarded as a raw schema, and the provider decides whether to accept it.

## Development

```bash
mvn test
```

Copyright 2026 MongoDB, Inc. Licensed under the Apache License, Version 2.0.
