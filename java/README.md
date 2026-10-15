# Atlas Agent Engine Java SDKs

Java SDK packages for MongoDB's Atlas Agent Engine. They need Java 17 or later.

## Packages

Use `agent-engine-sdk-memory` if your application reads or writes project memory.

| Package | Description |
|---|---|
| [`agent-engine-sdk-memory`](packages/agent-engine-sdk-memory) | Project memory (semantic, episodic, taxonomic, procedural and custom types) |
| [`agent-engine-sdk`](packages/agent-engine-sdk) | Framework-neutral core protocols, models, and HTTP clients |

Framework SDKs (LangGraph, Google ADK) and the shared runner are not available for Java.

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
