package com.mongodb.agentengine.runner.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.agentengine.runner.RunnableApp;
import com.mongodb.agentengine.runner.RunnerConfig;
import com.mongodb.agentengine.runner.RunnerMode;
import com.mongodb.agentengine.runner.models.InvokeLlmArguments;
import com.mongodb.agentengine.runner.models.LlmPodInvokeRequest;
import com.mongodb.agentengine.runner.models.LlmPodInvokeResponse;
import com.mongodb.agentengine.runner.models.LlmPodStreamEvent;
import com.mongodb.agentengine.runner.models.ToolPodExecuteRequest;
import com.mongodb.agentengine.runner.models.ToolPodExecuteResponse;
import com.mongodb.agentengine.sdk.BaseLLM;
import com.mongodb.agentengine.sdk.Json;
import com.mongodb.agentengine.sdk.LLMResponse;
import com.mongodb.agentengine.sdk.LLMStreamChunk;
import com.mongodb.agentengine.sdk.LLMTokenUsage;
import com.mongodb.agentengine.sdk.ToolDefinition;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The tool server: runs the remote tools and LLM calls the orchestration engine routes to it.
 *
 * <p>Remote tools and models run here, not in the agent runtime, so the credentials they need can be
 * given to this process alone. The OE calls {@code /execute} for a tool, and {@code /invoke_llm} or
 * {@code /invoke_llm/stream} for a model call, which this server answers with the LLM the app
 * registered under the call's {@code llm_id}.
 */
public final class ToolServer extends ComponentServer {

  private static final System.Logger LOG = System.getLogger(ToolServer.class.getName());

  private final String podName = hostName();
  private volatile boolean registrationsLoaded;

  public ToolServer(RunnableApp app, RunnerConfig config) throws IOException {
    super(app, RunnerMode.TOOL, config.host(), config.portFor(RunnerMode.TOOL));
    http().route("POST", "/execute", request -> JsonHttpServer.Reply.ok(execute(request.json(ToolPodExecuteRequest.class))));
    http().route("POST", "/invoke_llm", request -> JsonHttpServer.Reply.ok(invokeLlm(parseLlmRequest(request.body()))));
    http().route("POST", "/invoke_llm/stream", request -> streamLlm(parseLlmRequest(request.body())));
    http().route("POST", "/guardrails/check", request -> {
      throw new HttpError(501, "/guardrails/check is not implemented yet");
    });
  }

  // ---------------------------------------------------------------------------
  // Tools
  // ---------------------------------------------------------------------------

  private ToolPodExecuteResponse execute(ToolPodExecuteRequest request) {
    ToolDefinition tool = app().findTool(request.toolName()).orElse(null);
    if (tool == null) {
      return failure("Unknown tool: " + request.toolName());
    }
    try {
      Object value = tool.callable().call(request.arguments());
      JsonNode result = Json.mapper().valueToTree(value);
      return new ToolPodExecuteResponse("success", result, null, podName, null, Map.of(), true);
    } catch (Exception e) {
      LOG.log(System.Logger.Level.WARNING, "Tool " + request.toolName() + " failed", e);
      return failure(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
    }
  }

  private ToolPodExecuteResponse failure(String error) {
    return new ToolPodExecuteResponse("error", null, error, podName, null, Map.of(), true);
  }

  // ---------------------------------------------------------------------------
  // LLM
  // ---------------------------------------------------------------------------

  private static LlmPodInvokeRequest parseLlmRequest(byte[] body) {
    try {
      return LlmPodInvokeRequest.parse(body);
    } catch (IOException e) {
      throw new HttpError(422, "Invalid request body: " + e.getMessage());
    }
  }

  private LlmPodInvokeResponse invokeLlm(LlmPodInvokeRequest request) {
    long started = System.nanoTime();
    try {
      LLMResponse response = LLMResponse.fromChunks(chunks(request).toList());
      return new LlmPodInvokeResponse("success", Json.mapper().valueToTree(response), null, null, podName, millisSince(started), response.usage());
    } catch (RuntimeException e) {
      return new LlmPodInvokeResponse("error", null, describe(e), null, podName, millisSince(started), null);
    }
  }

  private JsonHttpServer.Reply streamLlm(LlmPodInvokeRequest request) {
    return JsonHttpServer.Reply.events(sink -> {
      long started = System.nanoTime();
      LLMTokenUsage usage = null;
      try (Stream<LLMStreamChunk> chunks = chunks(request)) {
        for (LLMStreamChunk chunk : (Iterable<LLMStreamChunk>) chunks::iterator) {
          if (chunk.usage() != null) {
            usage = chunk.usage();
          }
          if (hasPayload(chunk)) {
            sink.send(LlmPodStreamEvent.delta(chunk.content(), chunk.toolCalls(), chunk.id(), chunk.name(), chunk.additionalKwargs(), chunk.responseMetadata()));
          }
        }
        sink.send(LlmPodStreamEvent.done(podName, millisSince(started), usage));
      } catch (IOException e) {
        throw e;
      } catch (RuntimeException e) {
        LOG.log(System.Logger.Level.WARNING, "LLM stream failed", e);
        sink.send(LlmPodStreamEvent.failed(describe(e), null));
      }
    });
  }

  /** Runs the registered provider for a call. Opening the stream starts the model. */
  private Stream<LLMStreamChunk> chunks(LlmPodInvokeRequest request) {
    InvokeLlmArguments arguments = request.arguments();
    BaseLLM provider = resolveProvider(arguments.llmId());
    return provider.stream(arguments.messages(), providerOptions(arguments));
  }

  /**
   * Finds the provider for an LLM id. The agent registers LLMs while it builds, so the first call
   * for an unknown id builds the agent once to collect the registrations.
   */
  private BaseLLM resolveProvider(String llmId) {
    var registered = app().registeredLlm(llmId);
    if (registered.isEmpty() && !registrationsLoaded) {
      synchronized (this) {
        if (!registrationsLoaded) {
          try {
            app().buildAgent();
          } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Building the agent to collect LLM registrations failed", e);
          }
          registrationsLoaded = true;
        }
      }
      registered = app().registeredLlm(llmId);
    }
    return registered.orElseThrow(() -> new IllegalArgumentException("No LLM is registered under id '" + llmId + "'")).provider();
  }

  /**
   * The keyword arguments a provider receives: the call's options, plus the reserved keys {@code
   * model}, {@code stop}, {@code tools} and {@code tool_choice}.
   */
  private static Map<String, Object> providerOptions(InvokeLlmArguments arguments) {
    Map<String, Object> kwargs = new LinkedHashMap<>();
    if (arguments.options() != null && arguments.options().isObject()) {
      kwargs.putAll(Json.mapper().convertValue(arguments.options(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}));
    }
    kwargs.put("model", arguments.model());
    if (arguments.stop() != null) {
      kwargs.put("stop", arguments.stop());
    }
    if (arguments.tools() != null && !arguments.tools().isEmpty()) {
      kwargs.put("tools", arguments.tools());
    }
    if (arguments.toolChoice() != null && !arguments.toolChoice().isNull()) {
      kwargs.put("tool_choice", arguments.toolChoice().isTextual() ? arguments.toolChoice().asText() : arguments.toolChoice());
    }
    return kwargs;
  }

  private static boolean hasPayload(LLMStreamChunk chunk) {
    return (chunk.content() != null && !chunk.content().isEmpty())
        || (chunk.toolCalls() != null && !chunk.toolCalls().isEmpty())
        || chunk.id() != null
        || chunk.name() != null
        || chunk.responseMetadata() != null
        || chunk.additionalKwargs() != null;
  }

  private static String describe(RuntimeException e) {
    return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
  }

  private static double millisSince(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000.0;
  }

  private static String hostName() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException e) {
      return "unknown";
    }
  }
}
