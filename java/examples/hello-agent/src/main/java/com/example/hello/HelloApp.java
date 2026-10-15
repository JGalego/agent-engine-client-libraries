package com.example.hello;

import com.mongodb.agentengine.runner.RunnableApp;
import com.mongodb.agentengine.sdk.BaseAgent;
import java.util.function.Supplier;

/** The agent app the runner loads, named in {@code agent.yaml} as {@code com.example.hello.HelloApp:app}. */
public final class HelloApp {

  public static final RunnableApp app = create();

  private HelloApp() {}

  private static RunnableApp create() {
    RunnableApp app = new RunnableApp("hello-java");
    Supplier<BaseAgent> builder = GreeterAgent::new;
    app.entrypoint(builder);
    return app;
  }
}
