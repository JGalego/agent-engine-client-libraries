# agent-engine-sdk-langgraph4j

[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

The LangGraph4j framework SDK for Atlas Agent Engine in Java. It runs a
[LangGraph4j](https://github.com/langgraph4j/langgraph4j) graph as a platform agent. The model calls
and tool calls the graph makes go through the Orchestration Engine (OE), which audits them and runs
them on the tool server. Counterparts exist for Python and TypeScript.

## Install

```xml
<dependency>
  <groupId>com.mongodb.agentengine</groupId>
  <artifactId>agent-engine-sdk-langgraph4j</artifactId>
  <version>0.11.9</version>
</dependency>
```

## An agent

```java
public final class GraphApp {

  public static final App app = create();

  private static App create() {
    App app = new App("assistant");

    app.tool(
        ToolDefinition.builder("get_current_date", "Today's date", schema, args -> LocalDate.now().toString())
            .build());

    ChatModel model = app.chatModel("gpt-4o-mini", provider);   // provider: a BaseLLM
    app.graph(() -> app.compile(app.agentExecutor(model).systemMessage(SystemMessage.from("Be brief.")).build()));
    return app;
  }
}
```

Name it in `agent.yaml` as `entrypoint: com.example.GraphApp:app` and run it with the
[runner](../agent-engine-runner-shared). See [`examples/graph-agent`](../../examples/graph-agent) for
a complete agent and how to run it in the local stack.

| Method | What it does |
|---|---|
| `tool(ToolDefinition)` | Registers a tool. The OE approves each call and runs it. |
| `chatModel(model, provider)` | Registers a model and returns a LangChain4j `ChatModel` over it. `provider` is the `BaseLLM` the tool server runs, such as a `LangChain4jLLM`. |
| `streamingChatModel(model, provider)` | The same, as a `StreamingChatModel`. |
| `agentExecutor(model)` | LangGraph4j's agent executor builder, wired to the app's tools. |
| `compile(graph)` | Compiles a graph with the app's checkpointer. |
| `graph(supplier)` | Registers the graph builder. The graph is rebuilt for every execution. |

Any graph over chat messages works, not only the agent executor. In your own nodes, call tools with
`app.callTool(...)` and models through the handle `app.llm(...)` returns, so the OE sees them.

## Conversation state

Each execution adds the caller's message to the session's conversation and runs the graph. The
conversation is keyed by session and workspace, so sessions never share state, and a follow-up turn
remembers the earlier ones.

The checkpointer is in memory. A session keeps its sandbox for as long as it is active, so the
conversation lasts as long as that process does. A durable store is planned.

## Threads

LangGraph4j runs graph nodes on its own worker threads. The SDK runs them on an executor that carries
the current execution along, so tool and model calls made from a node reach the OE for the right
execution. Work you start on your own threads needs `ExecutionScope.wrap`.

## Not yet supported

Human-in-the-loop interrupts and resume, a durable checkpointer, and session-history queries are not
implemented. See the [runner README](../agent-engine-runner-shared/README.md) for what the runtime
covers.

## Development

```bash
mvn test
```

Copyright 2026 MongoDB, Inc. Licensed under the Apache License, Version 2.0.
