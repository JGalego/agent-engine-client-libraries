package com.example.approval;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.mongodb.agentengine.runner.RunnableApp;
import com.mongodb.agentengine.sdk.AgentInput;
import com.mongodb.agentengine.sdk.AgentOutput;
import com.mongodb.agentengine.sdk.BaseAgent;
import com.mongodb.agentengine.sdk.RequestContext;
import com.mongodb.agentengine.sdk.StreamEvent;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Asks for approval before it acts. The first turn suspends in the review tool; the turn the
 * reviewer resumes carries their decision in the request context.
 */
final class ApprovalAgent implements BaseAgent {

  static final String REVIEW_TOOL = "request_review";

  private final RunnableApp app;

  ApprovalAgent(RunnableApp app) {
    this.app = app;
  }

  @Override
  public AgentOutput execute(RequestContext ctx, AgentInput input) {
    if (ctx.resume()) {
      // The platform delivers the reviewer's answer under the key of the resume request body.
      String decision = ctx.resumeData() == null ? "" : ctx.resumeData().path("human_review").path("decision").asText("");
      String answer = "approve".equalsIgnoreCase(decision) ? "Approved, going ahead." : "Not approved (" + decision + "), nothing was done.";
      return new AgentOutput(JsonNodeFactory.instance.textNode(answer));
    }
    String request = input.payload().path("message").asText("");
    // Suspends the execution; the call does not return on this turn.
    app.callTool(REVIEW_TOOL, Map.of("task_description", request));
    throw new IllegalStateException("The review tool returned without suspending");
  }

  @Override
  public Stream<StreamEvent> stream(RequestContext ctx, AgentInput input) {
    return Stream.of(new StreamEvent(execute(ctx, input).response()));
  }
}
