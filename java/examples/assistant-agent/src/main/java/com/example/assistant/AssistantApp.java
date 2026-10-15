package com.example.assistant;

import com.mongodb.agentengine.runner.RunnableApp;
import com.mongodb.agentengine.runner.SecureLlm;
import com.mongodb.agentengine.sdk.BaseAgent;
import com.mongodb.agentengine.sdk.ToolDefinition;
import java.time.LocalDate;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The agent app the runner loads, named in {@code agent.yaml} as {@code
 * com.example.assistant.AssistantApp:app}.
 *
 * <p>It registers one tool and one model. Both run through the orchestration engine, which audits
 * them and runs them on the tool server, where the OpenAI key lives.
 */
public final class AssistantApp {

  static final String MODEL = System.getenv().getOrDefault("OPENAI_MODEL", "gpt-4o-mini");

  public static final RunnableApp app = create();

  private AssistantApp() {}

  private static RunnableApp create() {
    RunnableApp app = new RunnableApp("assistant-java");

    app.tool(
        ToolDefinition.builder(
                "get_current_date",
                "Returns today's date as an ISO-8601 string.",
                Map.of("type", "object", "properties", Map.of()),
                arguments -> LocalDate.now().toString())
            .build());

    SecureLlm llm = app.llm(MODEL, new OpenAiProvider(MODEL));
    Supplier<BaseAgent> builder = () -> new AssistantAgent(app, llm.bindTools(app.toolSchemas()));
    app.entrypoint(builder);
    return app;
  }
}
