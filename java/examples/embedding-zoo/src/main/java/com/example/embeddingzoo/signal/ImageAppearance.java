package com.example.embeddingzoo.signal;

import com.example.embeddingzoo.Vectors;
import java.awt.image.BufferedImage;
import java.util.List;

/** 4×4×4 RGB histogram. This is an appearance vector, not a captioning or CLIP embedding. */
public final class ImageAppearance {

  public static final int DIMENSIONS = 64;

  private ImageAppearance() {}

  public static List<Double> embed(BufferedImage image) {
    double[] bins = new double[DIMENSIONS];
    int width = image.getWidth();
    int height = image.getHeight();
    int stepX = Math.max(1, width / 128);
    int stepY = Math.max(1, height / 128);
    int count = 0;
    for (int y = 0; y < height; y += stepY) {
      for (int x = 0; x < width; x += stepX) {
        int rgb = image.getRGB(x, y);
        int red = (rgb >> 16) & 0xFF;
        int green = (rgb >> 8) & 0xFF;
        int blue = rgb & 0xFF;
        int bin = (red / 64) * 16 + (green / 64) * 4 + (blue / 64);
        bins[bin] += 1;
        count++;
      }
    }
    if (count > 0) {
      for (int i = 0; i < bins.length; i++) {
        bins[i] /= count;
      }
    }
    return Vectors.normalize(bins);
  }
}
