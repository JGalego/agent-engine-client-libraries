package com.example.nearmiss;

import com.mongodb.agentengine.runner.RunnableApp;
import com.mongodb.agentengine.runner.SecureLlm;
import com.mongodb.agentengine.sdk.BaseAgent;
import com.mongodb.agentengine.sdk.ToolDefinition;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The agent app the runner loads, named in {@code agent.yaml} as {@code
 * com.example.nearmiss.NearMissApp:app}.
 *
 * <p>The tools run on the tool server, where the database and embedding model are configured.
 */
public final class NearMissApp {

  static final String MODEL = System.getenv().getOrDefault("OPENAI_MODEL", "gpt-4o-mini");

  private static final List<String> KINDS = List.of(Datasets.KIND_DEVICE, Datasets.KIND_VEHICLE, Datasets.KIND_AVIATION);
  private static final List<String> DATASET_IDS = List.of(Datasets.MAUDE_ID, Datasets.NHTSA_ID, Datasets.ASRS_ID);

  public static final RunnableApp app = create();

  private NearMissApp() {}

  private static RunnableApp create() {
    RunnableApp app = new RunnableApp("near-miss");
    app.tool(tool(
        "semantic_search",
        "Searches public safety reports by meaning: medical device adverse events from FDA MAUDE, vehicle "
            + "complaints from NHTSA, and aviation safety reports from NASA ASRS. Every record is embedded with the "
            + "same text model.",
        Map.of(
            "type", "object",
            "properties", Map.of(
                "query", Map.of("type", "string", "description", "Natural-language description of what to find."),
                "kind", Map.of("type", "string", "enum", KINDS),
                "dataset_id", Map.of("type", "string", "enum", DATASET_IDS),
                "limit", Map.of("type", "integer")),
            "required", List.of("query")),
        180));
    app.tool(tool(
        "filter_metadata",
        "Returns records that match kind, dataset_id, and metadata equality filters. This is not a vector search.",
        Map.of(
            "type", "object",
            "properties", Map.of(
                "kind", Map.of("type", "string", "enum", KINDS),
                "dataset_id", Map.of("type", "string", "enum", DATASET_IDS),
                "metadata", Map.of("type", "object"),
                "limit", Map.of("type", "integer")),
            "required", List.of()),
        60));
    app.tool(tool(
        "correlate_evidence",
        "Compares records. Pass ids, or a query that retrieves the nearest record of each kind. "
            + "verified_connection is true only for shared provenance. analogous_pattern is cosine similarity "
            + "across datasets and is not a verified connection.",
        Map.of(
            "type", "object",
            "properties", Map.of(
                "ids", Map.of("type", "array", "items", Map.of("type", "string")),
                "query", Map.of("type", "string")),
            "required", List.of()),
        180));
    SecureLlm llm = app.llm(MODEL, new OpenAiChatProvider(MODEL));
    Supplier<BaseAgent> builder = () -> new NearMissAgent(app, llm.bindTools(app.toolSchemas()));
    app.entrypoint(builder);
    return app;
  }

  private static ToolDefinition tool(String name, String description, Map<String, Object> schema, int timeout) {
    return ToolDefinition.builder(name, description, schema, arguments -> NearMissServices.dispatch(NearMissServices.toolkit(), name, arguments))
        .timeoutSeconds(timeout)
        .network(List.of("api.openai.com", "huggingface.co"))
        .build();
  }
}
