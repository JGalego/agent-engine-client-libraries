package com.example.graph;

import com.mongodb.agentengine.sdk.ToolDefinition;
import com.mongodb.agentengine.sdk.langgraph4j.App;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.ChatModel;
import java.time.LocalDate;
import java.util.Map;

/**
 * A LangGraph4j agent, named in {@code agent.yaml} as {@code com.example.graph.GraphApp:app}.
 *
 * <p>The graph is LangGraph4j's ready-made agent executor: the model decides, a tool node runs the
 * tools it asks for, and the loop repeats until it answers. The model and the tool run through the
 * orchestration engine. The conversation is kept per session, so a follow-up turn remembers the
 * first.
 */
public final class GraphApp {

  static final String MODEL = System.getenv().getOrDefault("OPENAI_MODEL", "gpt-4o-mini");
  private static final String SYSTEM_PROMPT = "You are a concise assistant. Use your tools when they help. Answer in one or two sentences.";

  public static final App app = create();

  private GraphApp() {}

  private static App create() {
    App app = new App("graph-java");

    app.tool(
        ToolDefinition.builder(
                "get_current_date",
                "Returns today's date as an ISO-8601 string.",
                Map.of("type", "object", "properties", Map.of()),
                arguments -> LocalDate.now().toString())
            .build());

    ChatModel model = app.chatModel(MODEL, new OpenAiProvider(MODEL));
    app.graph(() -> {
      try {
        return app.compile(app.agentExecutor(model).systemMessage(SystemMessage.from(SYSTEM_PROMPT)).build());
      } catch (Exception e) {
        throw new IllegalStateException("Could not build the graph", e);
      }
    });
    return app;
  }
}
