# assistant-agent

A Java agent for Atlas Agent Engine that answers with an LLM and a tool. It shows the call path the
runtime provides: the agent never talks to OpenAI or runs the tool itself. Every call goes through the
Orchestration Engine (OE), which runs the model and the tool on the tool server.

| File | Purpose |
|---|---|
| [`AssistantApp.java`](src/main/java/com/example/assistant/AssistantApp.java) | Registers the `get_current_date` tool and the model |
| [`AssistantAgent.java`](src/main/java/com/example/assistant/AssistantAgent.java) | Asks the model, runs the tools it requests, repeats until it answers |
| [`OpenAiProvider.java`](src/main/java/com/example/assistant/OpenAiProvider.java) | The OpenAI model the tool server runs, through LangChain4j |
| [`agent.yaml`](agent.yaml) | Names the app: `com.example.assistant.AssistantApp:app` |

## Run it in the local stack

You need the [`agentengine` CLI](https://www.mongodb.com/docs/agentengine/get-started/), Docker,
JDK 17 with Maven, and an OpenAI API key.

1. Build the image:

   ```bash
   mvn -f ../../pom.xml package -DskipTests -pl examples/assistant-agent -am
   docker build -t assistant-java-agent:dev .
   ```

2. Get a dev stack. Scaffold the `hello-world-agent` template (its tool is also called
   `get_current_date`, which the stack's sandbox configuration expects) and start it:

   ```bash
   agentengine create --template hello-world-agent --name assistant --llm openai --yes
   cd assistant/agents/assistant && agentengine dev up
   ```

3. Replace the stack's `app` service with this image. Save as `java-override.yml`:

   ```yaml
   services:
     app:
       image: assistant-java-agent:dev
       build: !reset null
       working_dir: /opt/agent
       volumes: !reset []
       environment:
         OPENAI_API_KEY: "${OPENAI_API_KEY:?set OPENAI_API_KEY}"
       healthcheck:
         test: ["CMD-SHELL", "curl -fsS http://localhost:8001/health && curl -fsS http://localhost:8002/health"]
         interval: 5s
         timeout: 3s
         retries: 10
         start_period: 20s
   ```

   ```bash
   export OPENAI_API_KEY=...
   cd .agentengine
   docker compose -f docker-compose.dev.yml -f java-override.yml up -d
   ```

4. Invoke it:

   ```bash
   curl -X POST http://localhost:3000/invoke -H 'Content-Type: application/json' \
     -d '{"message": "What is the date today? Use your tool.", "user_id": "ana"}'
   # {"result":"Today's date is October 15, 2026.", ... "status":"completed"}
   ```

`OPENAI_MODEL` selects the model (default `gpt-4o-mini`).

The managed platform builds agent images remotely from Python or TypeScript sources, so a Java agent
runs in the local stack for now.
