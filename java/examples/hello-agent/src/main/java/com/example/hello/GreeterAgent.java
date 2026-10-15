package com.example.hello;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.mongodb.agentengine.sdk.AgentInput;
import com.mongodb.agentengine.sdk.AgentOutput;
import com.mongodb.agentengine.sdk.BaseAgent;
import com.mongodb.agentengine.sdk.RequestContext;
import com.mongodb.agentengine.sdk.StreamEvent;
import java.util.stream.Stream;

/** Greets the user. It needs no model, which keeps the example about the runtime. */
final class GreeterAgent implements BaseAgent {

  @Override
  public AgentOutput execute(RequestContext ctx, AgentInput input) {
    String message = input.payload().path("message").asText("");
    String who = ctx.userId() == null || ctx.userId().isBlank() ? "there" : ctx.userId();
    return new AgentOutput(JsonNodeFactory.instance.textNode("Hello " + who + ", from a Java agent! You said: " + message));
  }

  @Override
  public Stream<StreamEvent> stream(RequestContext ctx, AgentInput input) {
    return Stream.of(new StreamEvent(execute(ctx, input).response()));
  }
}
