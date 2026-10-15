package com.example.embeddingzoo.embed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.example.embeddingzoo.Vectors;
import com.example.embeddingzoo.ZooConfig;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Needs the model files from {@code scripts/download-sources.sh}; skipped without them. */
class MiniLmEmbedderTest {

  @Test
  void theInt8ModelAgreesWithTheFloatModelButKeepsItsOwnId() throws Exception {
    ZooConfig config = ZooConfig.load();
    assumeTrue(Files.isRegularFile(config.modelDir().resolve("model.onnx")));
    assumeTrue(Files.isRegularFile(config.modelDir().resolve("model_quantized.onnx")));

    try (MiniLmEmbedder full = MiniLmEmbedder.open(config);
        MiniLmEmbedder int8 = MiniLmEmbedder.open(config, true)) {
      String text = "A load balancer outage on Christmas Eve";
      List<Double> a = full.embed(text);
      List<Double> b = int8.embed(text);

      assertEquals(384, int8.dimensions());
      assertNotEquals(full.modelId(), int8.modelId());
      assertTrue(int8.modelId().endsWith(":int8"));
      assertTrue(Vectors.cosine(a, b) > 0.95, "int8 should stay close to float32");
    }
  }
}
