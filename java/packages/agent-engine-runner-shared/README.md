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
| Completion and error callbacks to the OE | done |
| Tool and LLM calls routed through the OE (`/tool/execute`, `/tool/result`, `/invoke_llm`) | planned |
| Streaming (`/stream/chunk`), node execution logs, session queries | planned |
| Suspend and resume, durable workflow (gRPC), drain, A2A | planned |

The tool server (port 8002) serves `/health`, `/` and `/tools`; its other routes answer `501`.

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

Then run `com.mongodb.agentengine.runner.Launcher` with the agent's classes on the classpath. See
[`examples/hello-agent`](../../examples/hello-agent) for a complete agent and image.

## Configuration

The platform stamps these on every runner container.

| Variable | Purpose |
|---|---|
| `RUNNER_MODE` | `aer` or `tool`: host one server. Wins over `AGENTIC_DEV_MODES`. |
| `AGENTIC_DEV_MODES` | Comma-separated modes to host in one process. Default `aer,tool`. |
| `APP_PORT`, `APP_HOST` | Port (single mode only) and interface. Defaults: 8001 / 8002 and `0.0.0.0`. |
| `OE_URL` | The OE. Callbacks always go here, never to a URL a request names. |
| `APP_ID`, `ORG_ID`, `PROJECT_ID` | Identity sent when advertising capabilities. |
| `AGENT_CONFIG` | Path of `agent.yaml`. Default `agent.yaml`. |

On startup the agent runtime tells the OE it is a Java runtime and refuses to serve if the OE does
not accept that.

## Development

```bash
mvn test
```

Copyright 2026 MongoDB, Inc. Licensed under the Apache License, Version 2.0.
