package com.example.embeddingzoo.dashboard;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/** Draws line charts from caller-supplied measurements. */
public final class ChartImage {

  static {
    System.setProperty("java.awt.headless", "true");
  }

  public record Series(String name, Color color, double[] values) {}

  private ChartImage() {}

  public static void write(
      Path png, int width, int height, String title, List<String> footer, List<Panel> panels) throws IOException {
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = image.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setColor(Color.WHITE);
    g.fillRect(0, 0, width, height);
    g.setColor(new Color(0x1b2838));
    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 26));
    g.drawString(title, 36, 42);
    int panelWidth = (width - 72) / panels.size();
    for (int i = 0; i < panels.size(); i++) {
      drawPanel(g, panels.get(i), 36 + i * panelWidth, 70, panelWidth - 24, height - 220);
    }
    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
    g.setColor(new Color(0x333333));
    int y = height - 140;
    for (String line : footer) {
      for (String wrapped : wrap(line, g.getFontMetrics(), width - 72)) {
        g.drawString(wrapped, 36, y);
        y += 18;
      }
    }
    g.dispose();
    Files.createDirectories(png.getParent());
    ImageIO.write(image, "png", png.toFile());
  }

  private static void drawPanel(Graphics2D g, Panel panel, int x, int y, int width, int height) {
    g.setColor(new Color(0x1b2838));
    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
    g.drawString(panel.title, x, y);
    int plotX = x + 56;
    int plotY = y + 16;
    int plotW = width - 70;
    int plotH = height - 70;
    g.setColor(new Color(0xe8eef4));
    g.fillRect(plotX, plotY, plotW, plotH);
    double min = Double.POSITIVE_INFINITY;
    double max = Double.NEGATIVE_INFINITY;
    for (Series series : panel.series) {
      for (double value : series.values) {
        min = Math.min(min, value);
        max = Math.max(max, value);
      }
    }
    if (min == Double.POSITIVE_INFINITY) {
      return;
    }
    if (max - min < 1e-9) {
      max = min + 1;
    }
    double pad = (max - min) * 0.08;
    min -= pad;
    max += pad;
    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
    for (int grid = 0; grid <= 4; grid++) {
      int gy = plotY + plotH - (plotH * grid / 4);
      g.setColor(new Color(0xc5d0dc));
      g.drawLine(plotX, gy, plotX + plotW, gy);
      String tick = tickLabel(min + (max - min) * grid / 4);
      g.setColor(new Color(0x333333));
      g.drawString(tick, plotX - 6 - g.getFontMetrics().stringWidth(tick), gy + 4);
    }
    g.setStroke(new BasicStroke(1.6f));
    for (Series series : panel.series) {
      g.setColor(series.color);
      int previousX = 0;
      int previousY = 0;
      boolean started = false;
      for (int i = 0; i < series.values.length; i++) {
        int px = plotX + (series.values.length == 1 ? plotW / 2 : plotW * i / (series.values.length - 1));
        int py = plotY + plotH - (int) Math.round((series.values[i] - min) / (max - min) * plotH);
        if (started) {
          g.drawLine(previousX, previousY, px, py);
        }
        previousX = px;
        previousY = py;
        started = true;
      }
    }
    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
    g.setColor(new Color(0x333333));
    g.drawString(panel.yLabel, x, plotY - 4);
    g.drawString(panel.xLabel, plotX, plotY + plotH + 18);
    int legendY = plotY + plotH + 38;
    int legendX = plotX;
    for (Series series : panel.series) {
      g.setColor(series.color);
      g.fillRect(legendX, legendY - 10, 12, 12);
      g.setColor(new Color(0x333333));
      g.drawString(series.name, legendX + 16, legendY);
      legendX += 180;
      if (legendX > plotX + plotW - 120) {
        legendX = plotX;
        legendY += 16;
      }
    }
  }

  private static String tickLabel(double value) {
    double magnitude = Math.abs(value);
    return magnitude != 0 && (magnitude < 0.01 || magnitude >= 10_000)
        ? String.format("%.1e", value)
        : String.format("%.3g", value);
  }

  private static List<String> wrap(String text, FontMetrics metrics, int maxWidth) {
    List<String> lines = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    for (String word : text.split(" ")) {
      if (current.length() > 0 && metrics.stringWidth(current + " " + word) > maxWidth) {
        lines.add(current.toString());
        current.setLength(0);
      }
      current.append(current.length() == 0 ? "" : " ").append(word);
    }
    lines.add(current.toString());
    return lines;
  }

  public record Panel(String title, String xLabel, String yLabel, List<Series> series) {}
}
