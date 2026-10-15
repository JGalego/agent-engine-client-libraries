package com.example.approval;

import com.mongodb.agentengine.runner.RunnableApp;
import com.mongodb.agentengine.sdk.BaseAgent;
import com.mongodb.agentengine.sdk.SuspendException;
import com.mongodb.agentengine.sdk.ToolDefinition;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The agent app the runner loads, named in {@code agent.yaml} as {@code
 * com.example.approval.ApprovalApp:app}.
 *
 * <p>Its one tool never returns. It suspends the execution, and the platform keeps it suspended
 * until a reviewer decides.
 */
public final class ApprovalApp {

  public static final RunnableApp app = create();

  private ApprovalApp() {}

  private static RunnableApp create() {
    RunnableApp app = new RunnableApp("approval-java");

    app.tool(
        ToolDefinition.builder(
                ApprovalAgent.REVIEW_TOOL,
                "Asks a human to approve or reject an action. Suspends the execution until they decide.",
                Map.of(
                    "type", "object",
                    "properties", Map.of("task_description", Map.of("type", "string")),
                    "required", List.of("task_description")),
                arguments -> {
                  throw new SuspendException(
                      "awaiting_human_review",
                      Map.of(
                          "task_description", String.valueOf(arguments.get("task_description")),
                          "allowed_decisions", List.of("approve", "reject")));
                })
            .build());

    Supplier<BaseAgent> builder = () -> new ApprovalAgent(app);
    app.entrypoint(builder);
    return app;
  }
}
