package com.example.embeddingzoo.dashboard;

import com.example.embeddingzoo.Datasets;
import com.example.embeddingzoo.Vectors;
import com.example.embeddingzoo.ZooConfig;
import com.example.embeddingzoo.ingest.CorpusManifest;
import com.example.embeddingzoo.ingest.ImsAcquisition;
import com.example.embeddingzoo.signal.ImsLayout;
import com.example.embeddingzoo.signal.ImsSignalFile;
import com.example.embeddingzoo.signal.VibrationFeatures;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mongodb.agentengine.sdk.Json;
import java.awt.Color;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Plots RMS, kurtosis, a waveform, and a spectrum from IMS snapshot files.
 *
 * <p>Every point is computed from a file listed in the manifest. The defect phrases in the footer
 * are the data-sheet notes for the end of the test.
 */
public final class ImsDashboard {

  private static final Color[] COLORS = {
    new Color(0x1b4f72), new Color(0x117a65), new Color(0xb03a2e), new Color(0xb9770e)
  };

  private ImsDashboard() {}

  public static List<Path> render(ZooConfig config, CorpusManifest manifest) throws IOException {
    Path root = ImsAcquisition.snapshots(config);
    List<Path> files = ImsAcquisition.snapshotFiles(root);
    if (files.isEmpty()) {
      throw new IOException("No IMS snapshot files under " + root);
    }
    int bearings = 4;
    double[][] rms = new double[bearings][files.size()];
    double[][] kurtosis = new double[bearings][files.size()];
    int columns = 0;
    for (int i = 0; i < files.size(); i++) {
      double[][] channels = ImsSignalFile.readChannels(files.get(i));
      columns = channels.length;
      for (int bearing = 1; bearing <= bearings; bearing++) {
        int channel = ImsLayout.representativeChannel(columns, bearing);
        if (channel < 0 || channel >= channels.length) {
          continue;
        }
        VibrationFeatures.Summary summary = VibrationFeatures.summarize(channels[channel]);
        rms[bearing - 1][i] = summary.rms();
        kurtosis[bearing - 1][i] = summary.standardizedKurtosis();
      }
      if ((i + 1) % 200 == 0) {
        System.err.println("Measured " + (i + 1) + " of " + files.size() + " snapshots");
      }
    }
    double[][] early = ImsSignalFile.readChannels(files.get(0));
    double[][] late = ImsSignalFile.readChannels(files.get(files.size() - 1));
    int focus = ImsLayout.representativeChannel(columns, 3);
    String earlyName = files.get(0).getFileName().toString();
    String lateName = files.get(files.size() - 1).getFileName().toString();
    String earlyTime = ImsSignalFile.timestampLabel(earlyName).orElse(earlyName);
    String lateTime = ImsSignalFile.timestampLabel(lateName).orElse(lateName);
    Path dir = config.home().resolve("dashboards");
    Path rmsPng = dir.resolve("ims-rms-kurtosis.png");
    Path wavePng = dir.resolve("ims-waveform-spectrum.png");
    List<String> footer = List.of(
        "Computed from " + files.size() + " extracted NASA IMS snapshot files. The set 1 readme lists 2,156 files. Amplitude is in the units stored in each file.",
        "RMS is the root mean square of the recorded samples. Kurtosis is the standardized fourth moment after subtracting the mean.",
        "Plotted channel is the first listed channel of each bearing (test 1: channels 1, 3, 5, and 7).",
        "Data-sheet note, for the end of test 1: inner race defect in bearing 3 and roller element defect in bearing 4.",
        "Source " + Datasets.IMS_PAGE + "  |  " + Datasets.IMS_CITATION);
    List<ChartImage.Series> rmsSeries = series(rms);
    List<ChartImage.Series> kurtosisSeries = series(kurtosis);
    ChartImage.write(rmsPng, 1600, 900, "NASA IMS bearings — measured RMS and kurtosis", footer, List.of(
        new ChartImage.Panel("RMS by snapshot order", "snapshot order (first to last file)", "RMS", rmsSeries),
        new ChartImage.Panel("Standardized kurtosis", "snapshot order (first to last file)", "kurtosis", kurtosisSeries)));
    int channel = Math.min(focus, early.length - 1);
    ChartImage.write(wavePng, 1600, 900, "NASA IMS bearings — bearing 3, first listed channel", footer, List.of(
        new ChartImage.Panel(
            "Waveform, max-abs buckets",
            earlyTime + " and " + lateTime,
            "recorded amplitude",
            List.of(
                new ChartImage.Series(earlyTime, COLORS[0], VibrationFeatures.downsampleMaxAbs(early[channel], 800)),
                new ChartImage.Series(lateTime, COLORS[2], VibrationFeatures.downsampleMaxAbs(late[channel], 800)))),
        new ChartImage.Panel(
            "Spectrum magnitude",
            "frequency bin (low to Nyquist)",
            "magnitude",
            List.of(
                new ChartImage.Series(earlyTime, COLORS[0], VibrationFeatures.spectrumBars(early[channel], 128)),
                new ChartImage.Series(lateTime, COLORS[2], VibrationFeatures.spectrumBars(late[channel], 128))))));
    writeProvenance(config, manifest, dir, files, rmsPng, wavePng);
    return List.of(rmsPng, wavePng);
  }

  private static List<ChartImage.Series> series(double[][] values) {
    List<ChartImage.Series> series = new ArrayList<>();
    for (int bearing = 0; bearing < values.length; bearing++) {
      series.add(new ChartImage.Series("bearing " + (bearing + 1), COLORS[bearing], values[bearing]));
    }
    return series;
  }

  private static void writeProvenance(
      ZooConfig config, CorpusManifest manifest, Path dir, List<Path> files, Path rmsPng, Path wavePng) throws IOException {
    Path root = ImsAcquisition.snapshots(config);
    Path zipSha = config.dataDir().resolve("ims").resolve("zip.sha256");
    ObjectNode node = Json.mapper().createObjectNode();
    node.put("generated_at", Instant.now().toString());
    node.put("dataset_id", manifest.datasetId);
    node.put("source_url", Datasets.IMS_PAGE);
    node.put("download_url", Datasets.IMS_DOWNLOAD);
    node.put("license", Datasets.IMS_LICENSE);
    node.put("license_url", Datasets.IMS_LICENSE_URL);
    node.put("citation", Datasets.IMS_CITATION);
    node.put("snapshots_plotted", files.size());
    node.put("zip_sha256_file", Files.isRegularFile(zipSha) ? Files.readString(zipSha).trim() : "");
    node.set("first_file", fileNode(root, files.get(0)));
    node.set("last_file", fileNode(root, files.get(files.size() - 1)));
    node.put(
        "measurement_note",
        "RMS, kurtosis, waveforms, and spectra were computed from the snapshot files named here. They are not simulated.");
    ArrayNode images = node.putArray("images");
    images.add(rmsPng.getFileName().toString());
    images.add(wavePng.getFileName().toString());
    Json.mapper().writerWithDefaultPrettyPrinter().writeValue(dir.resolve("provenance.json").toFile(), node);
  }

  private static ObjectNode fileNode(Path root, Path file) throws IOException {
    ObjectNode node = Json.mapper().createObjectNode();
    node.put("path", root.relativize(file).toString().replace('\\', '/'));
    node.put("sha256", Vectors.sha256(file));
    node.put("bytes", Files.size(file));
    node.put("timestamp_label", ImsSignalFile.timestampLabel(file.getFileName().toString()).orElse(""));
    return node;
  }
}
