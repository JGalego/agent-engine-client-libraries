package com.mongodb.agentengine.runner;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mongodb.agentengine.runner.models.InvokeLlmArguments;
import com.mongodb.agentengine.runner.models.LlmPodStreamEvent;
import com.mongodb.agentengine.runner.models.ToolExecuteRequest;
import com.mongodb.agentengine.runner.models.ToolExecuteResponse;
import com.mongodb.agentengine.runner.models.ToolResultRequest;
import com.mongodb.agentengine.sdk.Json;
import com.mongodb.agentengine.sdk.LLMResponse;
import com.mongodb.agentengine.sdk.LLMStreamChunk;
import com.mongodb.agentengine.sdk.LLMToolSchema;
import com.mongodb.agentengine.sdk.Message;
import com.mongodb.agentengine.sdk.ToolCallChunk;
import com.mongodb.agentengine.sdk.ToolDefinition;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * The tool and LLM calls of one execution, every one of them approved by the orchestration engine.
 *
 * <p>Nothing an agent calls runs unaudited: the runtime asks the OE for approval first, and the OE
 * applies policy, replays cached results, and runs remote tools and models itself. A local tool the OE
 * approves is run by this runtime, which then reports how it finished. Each call takes the next step
 * number of the execution.
 */
public final class SecureExecution {

  private static final Duration TOOL_TIMEOUT = Duration.ofSeconds(600);
  private static final Duration LLM_TIMEOUT = Duration.ofSeconds(600);
  private static final int APPROVAL_ATTEMPTS = 3;
  private static final int RESULT_ATTEMPTS = 3;

  private final RunnableApp app;
  private final OeClient oe;
  private final String executionId;
  private final Map<String, String> customHeaders;
  private final AtomicInteger step = new AtomicInteger();

  public SecureExecution(RunnableApp app, OeClient oe, String executionId, Map<String, String> customHeaders) {
    this.app = app;
    this.oe = oe;
    this.executionId = executionId;
    this.customHeaders = customHeaders;
  }

  /** The id of the execution. */
  public String executionId() {
    return executionId;
  }

  private int nextStep() {
    return step.incrementAndGet();
  }

  /** Makes sure later steps are numbered after one the OE has already used. */
  private void observeStep(Integer latest) {
    if (latest != null) {
      step.accumulateAndGet(latest, Math::max);
    }
  }

  // ---------------------------------------------------------------------------
  // Tools
  // ---------------------------------------------------------------------------

  /**
   * Calls a registered tool.
   *
   * @param toolCallId the model's id for the call, or null
   * @return the tool's result
   * @throws IllegalArgumentException if no such tool is registered
   * @throws PolicyDeniedException if the OE refuses the call or cannot be reached
   * @throws ToolExecutionException if the tool fails
   */
  public JsonNode callTool(String name, Map<String, Object> arguments, String toolCallId) {
    ToolDefinition tool = app.findTool(name).orElseThrow(() -> new IllegalArgumentException("Unknown tool: " + name));
    int callStep = nextStep();
    boolean local = !tool.remote() && tool.providerType() == null && tool.scopes().isEmpty();

    ToolExecuteResponse response =
        approve(
            new ToolExecuteRequest(
                executionId,
                name,
                Json.mapper().valueToTree(arguments),
                callStep,
                toolCallId,
                null,
                local,
                tool.redactFields(),
                tool.providerType(),
                tool.scopes(),
                Map.of(),
                customHeaders),
            TOOL_TIMEOUT);
    if (!response.proceed()) {
      throw new PolicyDeniedException(response.reason() == null ? "Policy denied" : response.reason());
    }
    observeStep(response.latestStepNumber());

    if ("callback".equals(response.routeTo())) {
      if (!local) {
        throw new ToolExecutionException("The OE returned a local route for a tool declared as remote: " + name);
      }
      return runLocal(tool, arguments, callStep, toolCallId);
    }
    if (response.routeTo() != null) {
      throw new ToolExecutionException("Unexpected OE tool route: " + response.routeTo());
    }

    String status = response.status() == null ? "error" : response.status();
    switch (status) {
      case "success":
        return response.result() == null ? JsonNodeFactory.instance.nullNode() : response.result();
      case "interrupted":
        return JsonNodeFactory.instance.objectNode().put("interrupted", true);
      case "suspend":
        throw new UnsupportedOperationException("Suspending an execution is not supported yet");
      case "error":
        throw new ToolExecutionException(response.error() == null ? "Unknown error" : response.error());
      default:
        throw new ToolExecutionException("Unexpected OE tool status: " + status);
    }
  }

  private JsonNode runLocal(ToolDefinition tool, Map<String, Object> arguments, int callStep, String toolCallId) {
    long started = System.nanoTime();
    JsonNode result = null;
    String error = null;
    try {
      Object value = tool.callable().call(arguments);
      result = Json.mapper().valueToTree(value);
    } catch (Exception e) {
      error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
    double durationMs = (System.nanoTime() - started) / 1_000_000.0;
    report(new ToolResultRequest(executionId, callStep, tool.name(), toolCallId, error == null ? "success" : "error", result, error, durationMs, null, Map.of()));
    if (error != null) {
      throw new ToolExecutionException(error);
    }
    return result;
  }

  private void report(ToolResultRequest result) {
    OeClient.Response response = oe.postJson("/tool/result", result, RESULT_ATTEMPTS);
    if (!response.ok()) {
      throw new ToolExecutionException("OE rejected result settlement with HTTP " + response.status());
    }
  }

  // ---------------------------------------------------------------------------
  // LLM
  // ---------------------------------------------------------------------------

  /**
   * Calls an LLM and streams the response.
   *
   * @param stream whether the OE should route a real streaming call
   * @return the chunks as they arrive; consuming the stream performs the call
   * @throws PolicyDeniedException if the OE refuses the call
   * @throws LlmInvocationException if the call fails
   */
  public Stream<LLMStreamChunk> streamLlm(
      String llmId, String model, List<Message> messages, List<LLMToolSchema> tools, JsonNode toolChoice, Map<String, Object> options) {
    int callStep = nextStep();
    InvokeLlmArguments arguments =
        new InvokeLlmArguments(
            model,
            messages,
            llmId,
            stopSequences(options),
            tools == null || tools.isEmpty() ? null : tools,
            toolChoice,
            options == null || options.isEmpty() ? null : withoutStop(options),
            true);
    ToolExecuteResponse response =
        approve(
            new ToolExecuteRequest(executionId, "invoke_llm", Json.mapper().valueToTree(arguments), callStep, null, null, true, List.of(), null, List.of(), Map.of(), customHeaders),
            LLM_TIMEOUT);
    observeStep(response.latestStepNumber());

    if (!response.proceed()) {
      // A guardrail block answers with the substitute text in place of model output.
      if (response.result() != null && response.result().isTextual()) {
        return Stream.of(new LLMStreamChunk(response.result().asText(), null, null, null, null, null, null));
      }
      throw new PolicyDeniedException(response.reason() == null ? "Policy denied" : response.reason());
    }
    JsonNode result = response.result() != null ? response.result() : response.cachedResult();
    String status = response.status() != null ? response.status() : (result != null ? "success" : null);

    if ("error".equals(status)) {
      throw new LlmInvocationException(response.error() == null ? "LLM call failed" : response.error(), response.errorCode());
    }
    if ("interrupted".equals(status)) {
      return Stream.of(interruptedChunk());
    }
    if (response.routeTo() != null && !"callback".equals(response.routeTo()) && result == null) {
      return relay(response.routeTo());
    }
    if (result == null) {
      throw new LlmInvocationException("Unexpected OE invoke_llm status: " + status, null);
    }
    return chunksOf(LLMResponse.fromRaw(result)).stream();
  }

  /** Runs an LLM call and collects the response. */
  public LLMResponse invokeLlm(
      String llmId, String model, List<Message> messages, List<LLMToolSchema> tools, JsonNode toolChoice, Map<String, Object> options) {
    try (Stream<LLMStreamChunk> chunks = streamLlm(llmId, model, messages, tools, toolChoice, options)) {
      return LLMResponse.fromChunks(chunks.toList());
    }
  }

  /** Reads the OE's audited relay of the tool server's stream. */
  private Stream<LLMStreamChunk> relay(String streamUrl) {
    List<LLMStreamChunk> chunks = new ArrayList<>();
    boolean[] finished = new boolean[1];
    int status =
        oe.postEvents(
            streamUrl,
            LLM_TIMEOUT,
            data -> {
              LlmPodStreamEvent event;
              try {
                event = Json.mapper().readValue(data, LlmPodStreamEvent.class);
              } catch (JsonProcessingException e) {
                return true; // a malformed event is skipped, as the platform's own runtimes do
              }
              if (Boolean.TRUE.equals(event.interrupted())) {
                finished[0] = true;
                chunks.add(interruptedChunk());
                return false;
              }
              if (event.error() != null) {
                throw new LlmInvocationException(event.error(), event.errorCode());
              }
              if (Boolean.TRUE.equals(event.done())) {
                finished[0] = true;
                if (event.usage() != null) {
                  chunks.add(new LLMStreamChunk(null, null, event.usage(), null, null, null, null));
                }
                return false;
              }
              chunkOf(event).ifPresent(chunks::add);
              return true;
            });
    if (status / 100 != 2) {
      throw new LlmInvocationException("The LLM stream failed with HTTP " + status, null);
    }
    if (!finished[0]) {
      throw new LlmInvocationException("The LLM stream ended without a done signal (truncated response)", null);
    }
    return chunks.stream();
  }

  private static java.util.Optional<LLMStreamChunk> chunkOf(LlmPodStreamEvent event) {
    List<ToolCallChunk> calls = new ArrayList<>();
    if (event.toolCallChunks() != null) {
      calls.addAll(event.toolCallChunks());
    }
    if (event.toolCalls() != null) {
      for (int i = 0; i < event.toolCalls().size(); i++) {
        var call = event.toolCalls().get(i);
        String args = call.args() == null ? null : (call.args().isTextual() ? call.args().asText() : call.args().toString());
        calls.add(new ToolCallChunk(call.id(), call.name(), args, call.type(), call.index() != null ? call.index() : i));
      }
    }
    boolean empty =
        (event.content() == null || event.content().isEmpty())
            && calls.isEmpty()
            && event.id() == null
            && event.name() == null
            && event.responseMetadata() == null
            && event.additionalKwargs() == null
            && event.usage() == null;
    if (empty) {
      return java.util.Optional.empty();
    }
    return java.util.Optional.of(
        new LLMStreamChunk(
            event.content() == null || event.content().isEmpty() ? null : event.content(),
            calls.isEmpty() ? null : calls,
            event.usage(),
            event.id(),
            event.name(),
            event.additionalKwargs(),
            event.responseMetadata()));
  }

  private static LLMStreamChunk interruptedChunk() {
    return new LLMStreamChunk(null, null, null, null, null, null, Map.of("interrupted", JsonNodeFactory.instance.booleanNode(true)));
  }

  /** A complete response expressed as stream chunks, for a call the OE answered without streaming. */
  private static List<LLMStreamChunk> chunksOf(LLMResponse response) {
    List<ToolCallChunk> calls = null;
    if (response.toolCalls() != null) {
      calls = new ArrayList<>();
      for (int i = 0; i < response.toolCalls().size(); i++) {
        var call = response.toolCalls().get(i);
        String args = call.args() == null ? null : (call.args().isTextual() ? call.args().asText() : call.args().toString());
        calls.add(new ToolCallChunk(call.id(), call.name(), args, call.type(), call.index() != null ? call.index() : i));
      }
    }
    return List.of(new LLMStreamChunk(response.content().isEmpty() ? null : response.content(), calls, response.usage(), response.id(), response.name(), response.additionalKwargs(), response.responseMetadata()));
  }

  private static List<String> stopSequences(Map<String, Object> options) {
    if (options == null) {
      return null;
    }
    Object stop = options.get("stop");
    if (stop instanceof List<?> list) {
      return list.stream().map(String::valueOf).toList();
    }
    return stop instanceof String text ? List.of(text) : null;
  }

  private static ObjectNode withoutStop(Map<String, Object> options) {
    ObjectNode node = Json.mapper().valueToTree(options);
    node.remove("stop");
    return node.isEmpty() ? null : node;
  }

  // ---------------------------------------------------------------------------
  // Approval
  // ---------------------------------------------------------------------------

  private ToolExecuteResponse approve(ToolExecuteRequest request, Duration timeout) {
    OeClient.Response response;
    try {
      response = oe.postJson("/tool/execute", request, APPROVAL_ATTEMPTS, timeout);
    } catch (java.io.UncheckedIOException e) {
      throw new PolicyDeniedException("OE unreachable; blocking for safety", e);
    }
    if (!response.ok()) {
      throw new PolicyDeniedException("OE unreachable; blocking for safety (HTTP " + response.status() + ")");
    }
    try {
      return Json.mapper().readValue(response.body(), ToolExecuteResponse.class);
    } catch (IOException e) {
      throw new PolicyDeniedException("OE returned an unreadable approval: " + e.getMessage(), e);
    }
  }
}
