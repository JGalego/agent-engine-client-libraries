package com.mongodb.agentengine.sdk.langgraph4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mongodb.agentengine.sdk.AgentInput;
import com.mongodb.agentengine.sdk.AgentOutput;
import com.mongodb.agentengine.sdk.BaseAgent;
import com.mongodb.agentengine.sdk.BaseLLM;
import com.mongodb.agentengine.sdk.LLMResponse;
import com.mongodb.agentengine.sdk.LLMStreamChunk;
import com.mongodb.agentengine.sdk.Message;
import com.mongodb.agentengine.sdk.RequestContext;
import com.mongodb.agentengine.sdk.StreamEvent;
import com.mongodb.agentengine.sdk.ToolDefinition;
import dev.langchain4j.data.message.SystemMessage;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.bsc.langgraph4j.CompiledGraph;
import org.junit.jupiter.api.Test;

class AppTest {

  private static final BaseLLM PROVIDER =
      new BaseLLM() {
        @Override
        public LLMResponse invoke(List<Message> messages, Map<String, Object> kwargs) {
          return new LLMResponse("x", null, null, null, null, null, null, null);
        }

        @Override
        public Stream<LLMStreamChunk> stream(List<Message> messages, Map<String, Object> kwargs) {
          return Stream.empty();
        }
      };

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

  private static ToolDefinition tool(String name) {
    return ToolDefinition.builder(name, "Does " + name, Map.of("type", "object", "properties", Map.of()), args -> "ok").build();
  }

  @Test
  void chatModelRegistersTheProviderForTheToolServer() {
    App app = new App("a");

    app.chatModel("gpt-x", PROVIDER);

    var registered = app.registeredLlm("__default__").orElseThrow();
    assertEquals("gpt-x", registered.model());
    assertSame(PROVIDER, registered.provider());
  }

  @Test
  void streamingChatModelRegistersTheSameWay() {
    App app = new App("a");

    app.streamingChatModel("gpt-x", PROVIDER);

    assertSame(PROVIDER, app.registeredLlm("__default__").orElseThrow().provider());
  }

  @Test
  void theAgentExecutorGraphIsBuiltOverEveryRegisteredTool() throws Exception {
    App app = new App("a");
    app.tool(tool("first"));
    app.tool(tool("second"));

    var graph = app.agentExecutor(new ScriptedChatModel().answer("x")).systemMessage(SystemMessage.from("be brief")).build();

    assertTrue(graph.compile() != null);
  }

  @Test
  void compiledGraphsShareTheAppsCheckpointer() throws Exception {
    App app = new App("a");
    var graph = app.agentExecutor(new ScriptedChatModel()).build();

    CompiledGraph<?> compiled = app.compile(graph);

    assertSame(app.checkpointer(), compiled.compileConfig.checkpointSaver().orElseThrow());
  }

  @Test
  void aGraphEntrypointBuildsALangGraphAgent() throws Exception {
    App app = new App("a");
    app.graph(() -> {
      try {
        return app.compile(app.agentExecutor(new ScriptedChatModel()).build());
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    });

    assertInstanceOf(LangGraph4jAgent.class, app.buildAgent());
  }

  @Test
  void aSupplierOfAnAgentIsUsedAsIs() {
    App app = new App("a");
    Supplier<BaseAgent> builder = () -> AGENT;

    app.entrypoint(builder);

    assertSame(AGENT, app.buildAgent());
  }

  @Test
  void aSupplierOfSomethingElseIsRejectedWhenBuilt() {
    App app = new App("a");
    Supplier<String> builder = () -> "nope";
    app.entrypoint(builder);

    assertThrows(IllegalStateException.class, app::buildAgent);
  }

  @Test
  void entrypointAndBuildNeedARegisteredBuilder() {
    App app = new App("a");

    assertThrows(IllegalArgumentException.class, () -> app.entrypoint("not a supplier"));
    assertThrows(IllegalStateException.class, app::buildAgent);
  }
}
