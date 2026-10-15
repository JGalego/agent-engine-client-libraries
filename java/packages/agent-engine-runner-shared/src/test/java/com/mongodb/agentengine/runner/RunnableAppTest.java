package com.mongodb.agentengine.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.mongodb.agentengine.sdk.AgentInput;
import com.mongodb.agentengine.sdk.AgentOutput;
import com.mongodb.agentengine.sdk.BaseAgent;
import com.mongodb.agentengine.sdk.RequestContext;
import com.mongodb.agentengine.sdk.StreamEvent;
import com.mongodb.agentengine.sdk.ToolDefinition;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class RunnableAppTest {

  private static final BaseAgent AGENT =
      new BaseAgent() {
        @Override
        public AgentOutput execute(RequestContext ctx, AgentInput input) {
          return new AgentOutput(input.payload());
        }

        @Override
        public Stream<StreamEvent> stream(RequestContext ctx, AgentInput input) {
          return Stream.empty();
        }
      };

  @Test
  void buildsTheAgentWithTheRegisteredBuilder() {
    RunnableApp app = new RunnableApp("a");
    Supplier<BaseAgent> builder = () -> AGENT;

    app.entrypoint(builder);

    assertSame(AGENT, app.buildAgent());
  }

  @Test
  void buildingWithoutAnEntrypointFails() {
    assertThrows(IllegalStateException.class, () -> new RunnableApp("a").buildAgent());
  }

  @Test
  void registersToolDefinitions() {
    RunnableApp app = new RunnableApp("a");
    ToolDefinition tool = ToolDefinition.builder("echo", "Echoes", Map.of(), args -> args).build();

    app.tool(tool);

    assertEquals(java.util.List.of(tool), app.getToolDefinitions());
  }

  @Test
  void rejectsOtherToolAndEntrypointArguments() {
    RunnableApp app = new RunnableApp("a");

    assertThrows(IllegalArgumentException.class, () -> app.tool("not a definition"));
    assertThrows(IllegalArgumentException.class, () -> app.tool());
    assertThrows(IllegalArgumentException.class, () -> app.entrypoint("not a supplier"));
  }
}
