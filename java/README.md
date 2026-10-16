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
| [`agent-engine-runner-shared`](packages/agent-engine-runner-shared) | Runner runtime that serves agents to the Orchestration Engine (partial; see its README) |

See the [`hello-agent`](examples/hello-agent), [`assistant-agent`](examples/assistant-agent),
[`graph-agent`](examples/graph-agent), [`approval-agent`](examples/approval-agent) and
[`near-miss`](examples/near-miss) examples for running a Java agent in the local stack.

## Install

```xml
<dependency>
  <groupId>com.mongodb.agentengine</groupId>
  <artifactId>agent-engine-sdk-memory</artifactId>
  <version>0.11.9</version>
</dependency>
```

To build from source instead, run the install script from a checkout, or pipe it from the branch you
want. It needs JDK 17+, Maven and git:

```bash
curl -fsSL https://raw.githubusercontent.com/JGalego/agent-engine-client-libraries/<branch>/scripts/install-java-sdk.sh | bash -s -- --branch <branch>
```

## Deploy to the managed platform

The platform's CLI and build accept Python and TypeScript agents only, so a Java agent can't be
deployed directly. [`scripts/package-java-agent.sh`](../scripts/package-java-agent.sh) wraps a built
example in a small Python package whose entrypoint starts the JVM, with the JRE from the `jdk4py`
wheel. It needs [uv](https://docs.astral.sh/uv/).

```bash
mvn package -DskipTests -pl examples/hello-agent -am
../scripts/package-java-agent.sh examples/hello-agent    # writes examples/hello-agent/target/platform
cd examples/hello-agent/target/platform
agentengine init && agentengine deploy --auto
```

Platform settings such as network egress, sandboxes and features go in a `platform.yaml` next to the
example's `agent.yaml`; without one the agent gets deny-all egress. `--include <path>[:<dest>]` ships
extra files, such as a model. Deploying also needs an Atlas cluster, which `agentengine deploy` sets up
for you.

This works today but isn't supported: it sidesteps the CLI's language check and depends on how the
runtime starts agents. `jdk4py` bundles OpenJDK under the GPL; check its terms for your use.

## Development

```bash
mvn verify                  # build and test every package
mvn javadoc:javadoc         # API reference, written to each package's target/reports/apidocs
```

Release notes are on the [GitHub releases](https://github.com/mongodb/agent-engine-client-libraries/releases) page.
