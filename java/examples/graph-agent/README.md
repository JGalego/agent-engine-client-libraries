# graph-agent

A LangGraph4j agent for Atlas Agent Engine. The graph is LangGraph4j's ready-made agent executor:
the model decides, a tool node runs the tools it asks for, and the loop repeats until it answers. The
model and the tool run through the Orchestration Engine (OE), and the conversation is kept per
session, so a follow-up turn remembers the first.

| File | Purpose |
|---|---|
| [`GraphApp.java`](src/main/java/com/example/graph/GraphApp.java) | Registers the tool and model, and builds the graph |
| [`OpenAiProvider.java`](src/main/java/com/example/graph/OpenAiProvider.java) | The OpenAI model the tool server runs, through LangChain4j |
| [`agent.yaml`](agent.yaml) | Names the app: `com.example.graph.GraphApp:app` |

## Run it in the local stack

You need the [`agentengine` CLI](https://www.mongodb.com/docs/agentengine/get-started/), Docker,
JDK 17 with Maven, and an OpenAI API key. The steps match the
[assistant-agent example](../assistant-agent/README.md); only the image differs.

1. Build the image:

   ```bash
   mvn -f ../../pom.xml package -DskipTests -pl examples/graph-agent -am
   docker build -t graph-java-agent:dev .
   ```

2. Start a dev stack and replace its `app` service with this image, as described in the assistant
   example, using `image: graph-java-agent:dev` in the override file.

3. Have a conversation. Reuse the `session_id` so the agent remembers:

   ```bash
   curl -X POST http://localhost:3000/invoke -H 'Content-Type: application/json' \
     -d '{"message": "My name is Ana and I love sailing.", "user_id": "ana", "session_id": "demo"}'
   curl -X POST http://localhost:3000/invoke -H 'Content-Type: application/json' \
     -d '{"message": "What is my name and what do I love?", "user_id": "ana", "session_id": "demo"}'
   # "Your name is Ana, and you love sailing."
   ```

`OPENAI_MODEL` selects the model (default `gpt-4o-mini`). The managed platform builds agent images
remotely from Python or TypeScript sources, so a Java agent runs in the local stack for now.
