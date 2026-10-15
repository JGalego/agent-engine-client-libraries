# Builds the runner image for this agent. Run `mvn package` first so target/lib exists.
FROM eclipse-temurin:17-jre

WORKDIR /opt/agent
COPY target/lib/ lib/
COPY target/hello-agent-*.jar lib/
COPY agent.yaml agent.yaml

ENTRYPOINT ["java", "-cp", "/opt/agent/lib/*", "com.mongodb.agentengine.runner.Launcher"]
