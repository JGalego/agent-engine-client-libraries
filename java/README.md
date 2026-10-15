# Atlas Agent Engine Java SDKs

Java SDK packages for MongoDB's Atlas Agent Engine. They need Java 17 or later.

## Packages

Use `agent-engine-sdk-memory` if your application reads or writes project memory.

| Package | Description |
|---|---|
| [`agent-engine-sdk-memory`](packages/agent-engine-sdk-memory) | Project memory (semantic, episodic, taxonomic, procedural and custom types) |
| [`agent-engine-sdk-langgraph4j`](packages/agent-engine-sdk-langgraph4j) | LangGraph4j framework SDK: run a graph as a platform agent |
| [`agent-engine-sdk-langchain4j`](packages/agent-engine-sdk-langchain4j) | LangChain4j adapters (chat model as `BaseLLM` and back, message and tool translation) |
| [`agent-engine-sdk`](packages/agent-engine-sdk) | Framework-neutral core protocols, models, and HTTP clients |
| [`agent-engine-runner-shared`](packages/agent-engine-runner-shared) | Runner runtime that serves agents to the Orchestration Engine (in progress) |

See the [`hello-agent`](examples/hello-agent), [`assistant-agent`](examples/assistant-agent) and
[`graph-agent`](examples/graph-agent) examples for running a Java agent in the local stack.

## Install

```xml
<dependency>
  <groupId>com.mongodb.agentengine</groupId>
  <artifactId>agent-engine-sdk-memory</artifactId>
  <version>0.11.9</version>
</dependency>
```

## Development

```bash
mvn verify                  # build and test every package
mvn javadoc:javadoc         # API reference, written to each package's target/reports/apidocs
```

Release notes are on the [GitHub releases](https://github.com/mongodb/agent-engine-client-libraries/releases) page.
