# agent-engine-runner-shared

[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

The Java runner runtime for Atlas Agent Engine. The platform's Orchestration Engine (OE) dispatches
agent turns to an *agent runtime* over HTTP and routes tool calls to a *tool server*. This package
implements both servers and the launcher that starts them for your agent.

> This is an internal core package; agents don't install it directly. A framework SDK that depends
> on it is planned.

This package is a work in progress. It currently covers:

| Piece | Status |
|---|---|
| Launcher: `agent.yaml`, entrypoint loading, `RUNNER_MODE` / `AGENTIC_DEV_MODES` | done |
| Agent runtime (port 8001): `/health`, `/`, `/tools`, `/execute`, `/warm-up` | done |
| Capability advertisement to the OE at startup | done |
| Mutual TLS to the OE (`TLS_*` variables, as in the managed platform) | done |
| Completion and error callbacks to the OE | done |
| Tool calls through the OE: local tools (`/tool/execute`, `/tool/result`) and remote tools (the OE runs them on the tool server) | done |
| LLM calls through the OE, streamed from the tool server and folded into a response | done |
| Tool server (port 8002): `/health`, `/`, `/tools`, `/execute`, `/invoke_llm`, `/invoke_llm/stream` | done |
| Streamed text: LLM tokens are forwarded to the OE as they arrive (`/stream/chunk`) | done |
| Node execution logs (`/node/execution`) for frameworks that report nodes | done |
| Session history: `/query/sessions` and `/query/sessions/{id}/messages`, from a `SessionQuery` the app sets | done |
| Suspend and resume (human in the loop): throw `SuspendException`, read the decision from `RequestContext.resumeData()` | done |
| Custom stream output, durable workflow (gRPC), drain and per-call interrupt, A2A, guardrail checks | planned |

## How an agent plugs in

An agent is a `RunnableApp`: it registers tools and a builder for its `BaseAgent`. The runtime
builds a fresh agent for every execution.

```java
public final class HelloApp {
  public static final RunnableApp app = create();

  private static RunnableApp create() {
    RunnableApp app = new RunnableApp("hello-java");
    Supplier<BaseAgent> builder = GreeterAgent::new;
    app.entrypoint(builder);
    return app;
  }
}
```

Name it in `agent.yaml` as `fully.qualified.Class:member`, where `member` is a static field or a
static no-argument method:

```yaml
name: hello-java
entrypoint: com.example.hello.HelloApp:app
```

Then run `com.mongodb.agentengine.runner.Launcher` with the agent's classes on the classpath.

## Tools and models

Agents never call a model or a remote tool directly. Every call goes through the orchestration engine,
which applies policy, records it, and runs it on the tool server, so secrets such as an LLM API key
only need to exist there.

```java
SecureLlm llm = app.llm("gpt-4o-mini", provider);        // register the model; get a handle
JsonNode date = app.callTool("get_current_date", Map.of()); // run a registered tool
LLMResponse answer = llm.bindTools(app.toolSchemas()).invoke(messages);
```

- `app.llm(model, provider)` registers `provider`, a `BaseLLM`, for the tool server to run, and returns a
  `SecureLlm` handle that agent code calls. The handle has no provider and no secrets, so it is safe to
  create in agent code. Pass a LangChain4j model with `agent-engine-sdk-langchain4j`.
- A tool is local (`remote(false)`) or remote (the default). The OE hands an approved local tool back
  to the agent runtime, which runs it and reports the result; it runs a remote tool on the tool
  server itself.
- Both handles work only while the agent runs a turn. To use them from another thread, wrap the task
  with `ExecutionScope.wrap`.
- Calls are numbered in order across the execution, and the OE can answer a repeated call from its
  cache.

Provider options reach the tool server's provider as keyword arguments, with the reserved keys `model`,
`stop`, `tools` and `tool_choice`.

See [`examples/hello-agent`](../../examples/hello-agent) for the smallest agent and
[`examples/assistant-agent`](../../examples/assistant-agent) for one that uses a model and a tool.

## Configuration

The platform stamps these on every runner container.

| Variable | Purpose |
|---|---|
| `RUNNER_MODE` | `aer` or `tool`: host one server. Wins over `AGENTIC_DEV_MODES`. |
| `AGENTIC_DEV_MODES` | Comma-separated modes to host in one process. Default `aer,tool`. |
| `APP_PORT`, `APP_HOST` | Port (single mode only) and interface. Defaults: 8001 / 8002 and `0.0.0.0`. |
| `OE_URL` | The OE. Callbacks always go here, never to a URL a request names. |
| `TLS_CERT`, `TLS_KEY`, `TLS_CA_CERT` | Mutual TLS to an `https://` OE, each as `_PATH` (a file) or `_PEM` (the PEM text). All three are required for https; the CA is the only one trusted. |
| `APP_ID`, `ORG_ID`, `PROJECT_ID` | Identity sent when advertising capabilities. |
| `AGENT_CONFIG` | Path of `agent.yaml`. Default `agent.yaml`. |

On startup the agent runtime tells the OE it is a Java runtime and refuses to serve if the OE does
not accept that.

## Development

```bash
mvn test
```

Copyright 2026 MongoDB, Inc. Licensed under the Apache License, Version 2.0.
