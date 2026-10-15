package com.example.embeddingzoo;

import com.mongodb.agentengine.runner.RunnableApp;
import com.mongodb.agentengine.runner.SecureLlm;
import com.mongodb.agentengine.sdk.BaseAgent;
import com.mongodb.agentengine.sdk.ToolDefinition;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The agent app the runner loads, named in {@code agent.yaml} as {@code
 * com.example.embeddingzoo.EmbeddingZooApp:app}.
 *
 * <p>The tools run on the tool server, where the database and embedding model are configured.
 */
public final class EmbeddingZooApp {

  static final String MODEL = System.getenv().getOrDefault("OPENAI_MODEL", "gpt-4o-mini");

  private static final List<String> MODALITIES = List.of("telemetry", "image", "log", "incident", "code");
  private static final List<String> DATASET_IDS =
      List.of(Datasets.IMS_ID, Datasets.MVTEC_ID, Datasets.LOG_ID, Datasets.INCIDENT_ID, Datasets.CODE_ID);

  public static final RunnableApp app = create();

  private EmbeddingZooApp() {}

  private static RunnableApp create() {
    RunnableApp app = new RunnableApp("embedding-zoo");
    app.tool(tool(
        "semantic_search",
        "Searches stored evidence by embedding similarity. The text space embeds measurement cards, log lines, "
            + "image directory labels, incident chunks, and READMEs with one text model. The vibration and image "
            + "spaces only accept like, the id of a record that already has that kind of vector. A natural-language "
            + "query is not embedded as vibration or as a color histogram.",
        Map.of(
            "type", "object",
            "properties", Map.of(
                "query", Map.of("type", "string", "description", "Natural-language query. Used in the text space."),
                "like", Map.of("type", "string", "description", "Id of a stored record whose vector is the query."),
                "space", Map.of("type", "string", "enum", List.of("text", "vibration", "image")),
                "modality", Map.of("type", "string", "enum", MODALITIES),
                "dataset_id", Map.of("type", "string", "enum", DATASET_IDS),
                "limit", Map.of("type", "integer")),
            "required", List.of()),
        180));
    app.tool(tool(
        "filter_metadata",
        "Returns records that match modality, dataset_id, and metadata equality filters. This is not a vector search.",
        Map.of(
            "type", "object",
            "properties", Map.of(
                "modality", Map.of("type", "string", "enum", MODALITIES),
                "dataset_id", Map.of("type", "string", "enum", DATASET_IDS),
                "metadata", Map.of("type", "object"),
                "limit", Map.of("type", "integer")),
            "required", List.of()),
        60));
    app.tool(tool(
        "correlate_evidence",
        "Compares records. Pass ids, or a query that retrieves the nearest text-space record in each modality. "
            + "verified_connection is true only for shared provenance. analogous_pattern is cosine similarity "
            + "across datasets and is not a verified connection. Do not compare vectors from different models.",
        Map.of(
            "type", "object",
            "properties", Map.of(
                "ids", Map.of("type", "array", "items", Map.of("type", "string")),
                "query", Map.of("type", "string")),
            "required", List.of()),
        180));
    SecureLlm llm = app.llm(MODEL, new OpenAiChatProvider(MODEL));
    Supplier<BaseAgent> builder = () -> new EmbeddingZooAgent(app, llm.bindTools(app.toolSchemas()));
    app.entrypoint(builder);
    return app;
  }

  private static ToolDefinition tool(String name, String description, Map<String, Object> schema, int timeout) {
    return ToolDefinition.builder(name, description, schema, arguments -> ZooServices.dispatch(ZooServices.toolkit(), name, arguments))
        .timeoutSeconds(timeout)
        .network(List.of(
            "api.openai.com",
            "huggingface.co",
            "phm-datasets.s3.amazonaws.com",
            "raw.githubusercontent.com",
            "api.github.com",
            "web.archive.org"))
        .build();
  }
}
