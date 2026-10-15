# agent-engine-sdk-memory

[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

Unified **Memory** facade for Atlas Agent Engine (Java). The counterpart of the Python
`agent-engine-sdk-memory` and TypeScript `@mongodb-js/agent-engine-sdk-memory` packages: Java
applications read from and write to the Memory Server through the same routes, with memory that
persists across turns and sessions.

## Install

```xml
<dependency>
  <groupId>com.mongodb.agentengine</groupId>
  <artifactId>agent-engine-sdk-memory</artifactId>
  <version>0.11.9</version>
</dependency>
```

## The `Memory` facade

`Memory` exposes every memory type behind one transport-agnostic surface:

| Type | Write | Read |
|---|---|---|
| Conversation turns (STM) | `recordTurn` | (feeds `buildContext`) |
| Semantic (facts) | `saveSemantic` | `searchSemantic`, `getSemantic` |
| Episodic (conversations) | `saveEpisode` | `searchEpisodes`, `listEpisodes` |
| Taxonomic (knowledge base) | `saveTaxonomic` | `searchTaxonomic`, `getTaxonomicTerm`, `listDomains` |
| Procedural (how-tos) | `saveProcedure` | `discoverProcedures`, `getProcedure` |
| Custom types (declared per project) | `save` | `retrieve` |
| Unified | — | `buildContext`, `search` |

Every method takes a request object built with a builder, and blocks until the server answers.
Lookups return `Optional`. The request and result types are in
`com.mongodb.agentengine.sdk.api.v1.memory`.

```java
try (Memory memory = Memory.builder()
    .serviceAccountToken(System.getenv("AGENTIC_MEMORY_SERVICE_ACCOUNT_TOKEN"))
    .projectId("my-project")                // set => project-scoped Gateway routes
    .build()) {                             // unset => flat OE routes

  memory.saveSemantic(
      CreateSemanticRequest.builder("favorite_color", "teal")
          .userId("u1")
          .metadata(Map.of("channel", "web", "priority", "high"))
          .build());

  List<MemoryChunk> hits =
      memory.searchSemantic(SearchSemanticRequest.builder("color").userId("u1").build());
}
```

### Identity

`userId`, `agentId` and `sessionId` are resolved per call, from the first of:

1. the request itself,
2. the context set with `memory.bind(...)`,
3. the runtime's ambient context, for runtimes that provide one.

`bind` returns a new facade and leaves the original alone, so one facade can serve many users:

```java
Memory forUser = memory.bind(MemoryRequestContext.of("u1", "thread_123"));
forUser.recordTurn(WriteTurnRequest.builder("user").content("Hello").build());
```

Blank values count as unset. When an operation needs a field that none of these supplies, it
throws `MemoryIdentityException`. A read that asks for a visibility other than `private` does not
borrow the ambient user as its filter, and episodic reads never inherit a session: pass one on the
request to filter by it.

The custom-type operations carry no identity: the platform stamps org, project, and user from the
request, so they need a backend that can do that (project-scoped Gateway routes, or an
execution-scoped runtime).

## Configuration

The builder takes these arguments, or reads the matching environment variables. An argument wins.

| Argument | Env var | Purpose |
|---|---|---|
| `serviceAccountToken` | `AGENTIC_MEMORY_SERVICE_ACCOUNT_TOKEN` | Service-account access token. With no `baseUrl`, a token selects the hosted Gateway. |
| `baseUrl` | `AGENTIC_MEMORY_BASE_URL` | Backend address. |
| `projectId` | `AGENTIC_MEMORY_PROJECT_ID` | Set => project-scoped Gateway routes; empty => flat OE routes. |

Blank environment values count as unset. Pass `httpClient(...)` for custom TLS or proxies, and
`timeout(...)` to change the 30 second default.

To plug in your own backend, implement `MemoryRuntime` (and `MemoryCrudClient` for the
type-specific operations) and use `Memory.of(runtime, client)`. A runtime that also implements
`AmbientIdentityRuntime` supplies per-request identity.

### Context token budget

`BuildContextRequest.maxTokens` sets an optional gross context-construction budget (not a fetch
cost). After retrieval and ranking, the server subtracts a 500-token formatting reserve, then
greedily selects whole memory chunks that fit in the remainder. Values at or below 500 leave no
budget for memories, and larger values can still yield empty context when no chunk fits.
`metadata().tokenCount()` reports formatted output only and excludes the reserve. Omit it to keep
the server default. Non-positive values throw `IllegalArgumentException` when the request is built.

## Errors

All exceptions are unchecked.

| Exception | When |
|---|---|
| `MemoryAuthException` | 401 or 403 |
| `MemoryRouteNotFoundException` | 404 on a core-loop request, with a hint about the route shape |
| `MemoryBadRequestException` | Other 4xx |
| `MemoryNotProvisionedException` | The project's memory runtime is not reachable yet |
| `MemoryServerException` | 5xx, or a success response with an unreadable body |
| `MemoryConnectionException` | The backend could not be reached, or timed out |
| `MemoryNotSupportedException` | A capability gap |
| `MemoryClientException` | A request rejected before it was sent, such as a malformed custom type name or tag |
| `MemoryIdentityException` | A required identity field could not be resolved |

The first six HTTP-related ones extend `MemoryApiException`, which carries the status, error code
and response body. `MemoryNotSupportedException` covers both client-side gaps and, on the
custom-type routes, capability gaps only the platform can report: a bare 404/405 (platform too old
to serve the route) or the gateway's structured 400 reporting custom memory types disabled. A
structured unknown-type 404 raises `MemoryBadRequestException`. Reads retry 502/503/504 up to twice;
creates are never retried, because they carry no idempotency key.

## Development

```bash
mvn test
```

Copyright 2026 MongoDB, Inc. Licensed under the Apache License, Version 2.0.
