package com.example.embeddingzoo.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ChartImageTest {

  @Test
  void writesAPngFromSuppliedSeries() throws Exception {
    Path png = Files.createTempFile("chart", ".png");
    double[] values = new double[32];
    for (int i = 0; i < values.length; i++) {
      values[i] = Math.sin(i / 4.0);
    }
    ChartImage.write(
        png,
        640,
        360,
        "synthetic fixture",
        List.of("These numbers were supplied by the test."),
        List.of(new ChartImage.Panel("sine", "index", "value", List.of(new ChartImage.Series("sine", Color.BLUE, values)))));
    BufferedImage image = ImageIO.read(png.toFile());
    assertEquals(640, image.getWidth());
    boolean colored = false;
    for (int y = 0; y < image.getHeight() && !colored; y += 3) {
      for (int x = 0; x < image.getWidth(); x += 3) {
        if (image.getRGB(x, y) != Color.WHITE.getRGB()) {
          colored = true;
        }
      }
    }
    assertTrue(colored);
  }
}
