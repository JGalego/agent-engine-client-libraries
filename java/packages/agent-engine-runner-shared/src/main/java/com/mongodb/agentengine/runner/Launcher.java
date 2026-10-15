package com.mongodb.agentengine.runner;

import com.mongodb.agentengine.runner.server.AerServer;
import com.mongodb.agentengine.runner.server.ComponentServer;
import com.mongodb.agentengine.runner.server.ToolServer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Starts the servers for an agent: reads {@code agent.yaml}, loads the app it names, and hosts the
 * configured modes until the process stops.
 *
 * <p>Configuration comes from the environment; see {@link RunnerConfig}. The agent's classes must be
 * on the classpath.
 */
public final class Launcher {

  private static final System.Logger LOG = System.getLogger(Launcher.class.getName());

  private Launcher() {}

  public static void main(String[] args) throws Exception {
    RunnerConfig config = RunnerConfig.fromEnv(System.getenv());
    AgentConfig agent = AgentConfig.load(Path.of(config.agentConfigPath()));
    RunnableApp app = AppLoader.load(agent.entrypoint());

    List<ComponentServer> servers = new ArrayList<>();
    for (RunnerMode mode : config.orderedModes()) {
      servers.add(switch (mode) {
        case AER -> new AerServer(app, config, agent.features(), agent.framework());
        case TOOL -> new ToolServer(app, config);
      });
    }
    Runtime.getRuntime().addShutdownHook(new Thread(() -> servers.forEach(ComponentServer::close)));
    for (ComponentServer server : servers) {
      server.start();
      LOG.log(System.Logger.Level.INFO, "{0} server listening on port {1} for app {2}", server.mode().wireName(), server.port(), app.name());
    }
    new CountDownLatch(1).await();
  }
}
