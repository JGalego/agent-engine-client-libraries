package com.mongodb.agentengine.runner.server;

import com.mongodb.agentengine.runner.RunnableApp;
import com.mongodb.agentengine.runner.RunnerConfig;
import com.mongodb.agentengine.runner.RunnerMode;
import java.io.IOException;

/**
 * The tool server: runs remote tool bodies and LLM calls the orchestration engine routes to it.
 *
 * <p>It currently serves health and tool listing. Tool execution and LLM invocation answer 501
 * until they are implemented.
 */
public final class ToolServer extends ComponentServer {

  public ToolServer(RunnableApp app, RunnerConfig config) throws IOException {
    super(app, RunnerMode.TOOL, config.host(), config.portFor(RunnerMode.TOOL));
    for (String path : new String[] {"/execute", "/invoke_llm", "/invoke_llm/stream", "/guardrails/check"}) {
      http().route("POST", path, request -> {
        throw new HttpError(501, path + " is not implemented yet");
      });
    }
  }
}
