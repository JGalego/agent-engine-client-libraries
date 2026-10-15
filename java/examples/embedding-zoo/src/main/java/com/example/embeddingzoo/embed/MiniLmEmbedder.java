package com.example.embeddingzoo.embed;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import com.example.embeddingzoo.Datasets;
import com.example.embeddingzoo.Vectors;
import com.example.embeddingzoo.ZooConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mean-pooled {@code sentence-transformers/all-MiniLM-L6-v2} through the Xenova ONNX export.
 *
 * <p>The ONNX file is the transformer stack. Pooling and L2 normalization happen here, which is
 * what the sentence-transformers library does before returning a vector.
 */
public final class MiniLmEmbedder implements TextEmbedder, AutoCloseable {

  static final int MAX_LENGTH = 256;

  private final WordPieceTokenizer tokenizer;
  private final OrtEnvironment environment;
  private final OrtSession session;
  private final int dimensions;

  private MiniLmEmbedder(WordPieceTokenizer tokenizer, OrtEnvironment environment, OrtSession session, int dimensions) {
    this.tokenizer = tokenizer;
    this.environment = environment;
    this.session = session;
    this.dimensions = dimensions;
  }

  public static MiniLmEmbedder open(ZooConfig config) throws IOException {
    Path dir = config.modelDir();
    Path vocab = dir.resolve("vocab.txt");
    Path model = dir.resolve("model.onnx");
    if (!Files.isRegularFile(vocab) || !Files.isRegularFile(model)) {
      throw new IOException(
          "MiniLM files are missing under " + dir + ". Run: mvn -pl examples/embedding-zoo exec:java -Dexec.args=download");
    }
    try {
      WordPieceTokenizer tokenizer = WordPieceTokenizer.fromVocabFile(vocab);
      OrtEnvironment environment = OrtEnvironment.getEnvironment();
      OrtSession.SessionOptions options = new OrtSession.SessionOptions();
      options.setIntraOpNumThreads(Math.max(1, Runtime.getRuntime().availableProcessors() / 2));
      OrtSession session = environment.createSession(model.toString(), options);
      int dimensions = readDimensions(session);
      return new MiniLmEmbedder(tokenizer, environment, session, dimensions);
    } catch (OrtException e) {
      throw new IOException("Could not load MiniLM from " + model, e);
    }
  }

  @Override
  public String modelId() {
    return Datasets.TEXT_MODEL;
  }

  @Override
  public String artifact() {
    return Datasets.TEXT_ARTIFACT;
  }

  @Override
  public int dimensions() {
    return dimensions;
  }

  @Override
  public synchronized List<Double> embed(String text) {
    long[] ids = tokenizer.encode(text == null ? "" : text, MAX_LENGTH);
    long[] mask = new long[ids.length];
    long[] types = new long[ids.length];
    for (int i = 0; i < ids.length; i++) {
      mask[i] = 1;
    }
    Map<String, OnnxTensor> inputs = new HashMap<>();
    try {
      inputs.put("input_ids", OnnxTensor.createTensor(environment, new long[][] {ids}));
      inputs.put("attention_mask", OnnxTensor.createTensor(environment, new long[][] {mask}));
      if (session.getInputNames().contains("token_type_ids")) {
        inputs.put("token_type_ids", OnnxTensor.createTensor(environment, new long[][] {types}));
      }
      try (OrtSession.Result result = session.run(inputs)) {
        float[] pooled = meanPool(result.get(0).getValue(), mask);
        return Vectors.normalize(toDoubles(pooled));
      }
    } catch (OrtException e) {
      throw new IllegalStateException("MiniLM inference failed", e);
    } finally {
      for (OnnxTensor tensor : inputs.values()) {
        tensor.close();
      }
    }
  }

  @Override
  public void close() {
    try {
      session.close();
    } catch (OrtException e) {
      throw new IllegalStateException(e);
    }
  }

  private static int readDimensions(OrtSession session) throws OrtException {
    TensorInfo info = (TensorInfo) session.getOutputInfo().values().iterator().next().getInfo();
    long[] shape = info.getShape();
    if (shape.length == 0) {
      return 384;
    }
    long last = shape[shape.length - 1];
    return last > 0 ? (int) last : 384;
  }

  private static float[] meanPool(Object value, long[] mask) {
    if (value instanceof float[][][] hidden) {
      int seq = hidden[0].length;
      int width = hidden[0][0].length;
      float[] pooled = new float[width];
      float weight = 0;
      for (int token = 0; token < seq && token < mask.length; token++) {
        if (mask[token] == 0) {
          continue;
        }
        weight++;
        for (int dim = 0; dim < width; dim++) {
          pooled[dim] += hidden[0][token][dim];
        }
      }
      if (weight > 0) {
        for (int dim = 0; dim < width; dim++) {
          pooled[dim] /= weight;
        }
      }
      return pooled;
    }
    if (value instanceof float[][] already) {
      return already[0];
    }
    throw new IllegalStateException("Unexpected MiniLM output type " + value.getClass().getName());
  }

  private static double[] toDoubles(float[] values) {
    double[] out = new double[values.length];
    for (int i = 0; i < values.length; i++) {
      out[i] = values[i];
    }
    return out;
  }
}
