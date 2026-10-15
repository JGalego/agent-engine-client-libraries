package com.mongodb.agentengine.runner.server;

import com.mongodb.agentengine.runner.RunnableApp;
import com.mongodb.agentengine.runner.RunnerMode;
import com.mongodb.agentengine.runner.models.HealthResponse;
import com.mongodb.agentengine.runner.models.ToolsListResponse;
import com.mongodb.agentengine.sdk.ToolDefinition;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Behavior shared by the runner's servers: the app they serve, health, and tool listing. A subclass
 * adds its mode's routes.
 */
public abstract class ComponentServer implements AutoCloseable {

  private final RunnableApp app;
  private final RunnerMode mode;
  private final JsonHttpServer http;

  protected ComponentServer(RunnableApp app, RunnerMode mode, String host, int port) throws IOException {
    this.app = app;
    this.mode = mode;
    this.http = new JsonHttpServer(host, port);
    http.route("GET", "/health", request -> JsonHttpServer.Reply.ok(health()));
    http.route("GET", "/", request -> JsonHttpServer.Reply.ok(Map.of("service", app.name(), "mode", mode.wireName(), "status", "running")));
    http.route("GET", "/tools", request -> JsonHttpServer.Reply.ok(tools()));
  }

  /** The app this server serves. */
  protected RunnableApp app() {
    return app;
  }

  /** The route table, for subclasses to register their own routes. */
  protected JsonHttpServer http() {
    return http;
  }

  public RunnerMode mode() {
    return mode;
  }

  /** The port the server is bound to. */
  public int port() {
    return http.port();
  }

  /** Mode-specific health details. */
  protected Map<String, Object> healthDetails() {
    List<String> names = new ArrayList<>();
    app.getToolDefinitions().forEach(tool -> names.add(tool.name()));
    return Map.of("tools_registered", names);
  }

  /**
   * Runs startup work that must succeed before the server accepts traffic, such as registering
   * with the orchestration engine.
   */
  protected void onStartup() throws Exception {}

  /** Runs startup work, then starts accepting requests. */
  public final void start() throws Exception {
    onStartup();
    http.start();
  }

  @Override
  public void close() {
    http.close();
  }

  private HealthResponse health() {
    return new HealthResponse("healthy", app.name(), mode.wireName(), "1.0.0", healthDetails());
  }

  private ToolsListResponse tools() {
    List<Map<String, Object>> listed = new ArrayList<>();
    for (ToolDefinition tool : app.getToolDefinitions()) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("name", tool.name());
      entry.put("description", tool.description());
      entry.put("is_local", !tool.remote());
      entry.put("provider_type", tool.providerType());
      entry.put("scopes", tool.scopes());
      entry.put("parameters", tool.argsSchema());
      listed.add(entry);
    }
    return new ToolsListResponse(listed, listed.size());
  }
}
