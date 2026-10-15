# agent-engine-sdk

[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

Core protocols, models, and HTTP clients shared by Atlas Agent Engine SDK integrations. The
package has no dependency on the platform runtime and can be used to define agent, execution,
message, tool, and LLM contracts in an independent application.

> This is an internal core package; applications don't use it directly. Add
> `agent-engine-sdk-memory`, which depends on it.

## Install

```xml
<dependency>
  <groupId>com.mongodb.agentengine</groupId>
  <artifactId>agent-engine-sdk</artifactId>
  <version>0.11.9</version>
</dependency>
```

## What's in it

| Package | Contents |
|---|---|
| `com.mongodb.agentengine.sdk` | Messages, content blocks, tool and LLM models, `BaseAgent`, `BaseLLM`, `BaseExecutionCallback`, `BaseApp`, events and sessions |
| `com.mongodb.agentengine.sdk.api` | Event API responses |
| `com.mongodb.agentengine.sdk.api.v1.memory` | Memory Server request and result types |
| `com.mongodb.agentengine.sdk.clients` | `EventClient` and `MemoryClient` |

Models are immutable records. They serialize to the platform's snake_case JSON through
`Json.mapper()`, and opaque JSON values (agent payloads, tool arguments) are Jackson `JsonNode`s.

```java
Message message = Message.user("What did we decide?");
String wire = Json.mapper().writeValueAsString(message);
// {"role":"user","content":"What did we decide?"}
```

The clients block and throw unchecked exceptions: `HttpStatusException` for a failed response,
`ResponseFormatException` for an unreadable body, and `UncheckedIOException` for network failures.
Lookups return `Optional.empty()` on a 404.

```java
MemoryClient client = MemoryClient.builder("http://127.0.0.1:8081").build();
client.writeTurn(WriteTurnRequest.builder("user").content("Hello").sessionId("thread_123").build());
```

Cancellation follows Java convention: honor thread interruption, and close the `Stream` returned
by `BaseAgent.stream` to stop an execution.

## Development

```bash
mvn test
```

Copyright 2026 MongoDB, Inc. Licensed under the Apache License, Version 2.0.
