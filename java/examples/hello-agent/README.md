# hello-agent

A minimal Java agent for Atlas Agent Engine. It greets the user and needs no model, which keeps
the example about the runtime: how an agent is declared, loaded, and served to the Orchestration
Engine (OE).

| File | Purpose |
|---|---|
| [`HelloApp.java`](src/main/java/com/example/hello/HelloApp.java) | The `RunnableApp` the runner loads |
| [`GreeterAgent.java`](src/main/java/com/example/hello/GreeterAgent.java) | The agent: one turn in, one answer out |
| [`agent.yaml`](agent.yaml) | Names the app: `com.example.hello.HelloApp:app` |
| [`Dockerfile`](Dockerfile) | Packages the agent and runner into a runner image |

## Run it in the local stack

You need the [`agentengine` CLI](https://www.mongodb.com/docs/agentengine/get-started/), Docker
and JDK 17 with Maven.

1. Build the image. `mvn package` also copies the runner's classpath into `target/lib`:

   ```bash
   mvn -f ../../pom.xml package -DskipTests -pl examples/hello-agent -am
   docker build -t hello-java-agent:dev .
   ```

2. Scaffold any Python agent project to get the dev stack (OE, MongoDB, memory server), and start
   it with `agentengine dev up`. The stack's generated `.agentengine/docker-compose.dev.yml` runs
   the agent in an `app` service.

3. Replace that service with this image. Save as `java-override.yml`:

   ```yaml
   services:
     app:
       image: hello-java-agent:dev
       build: !reset null
       working_dir: /opt/agent
       volumes: !reset []
       healthcheck:
         test: ["CMD-SHELL", "curl -fsS http://localhost:8001/health && curl -fsS http://localhost:8002/health"]
         interval: 5s
         timeout: 3s
         retries: 10
         start_period: 20s
   ```

   ```bash
   cd .agentengine
   docker compose -f docker-compose.dev.yml -f java-override.yml up -d
   ```

4. Invoke it. The scaffolded project's memory settings require a `user_id`:

   ```bash
   curl -X POST http://localhost:3000/invoke -H 'Content-Type: application/json' \
     -d '{"message": "Hello", "user_id": "ana"}'
   # {"result":"Hello ana, from a Java agent! You said: Hello", ... "status":"completed"}
   ```

The managed platform builds agent images remotely from Python or TypeScript sources, so a Java
agent runs in the local stack for now.
