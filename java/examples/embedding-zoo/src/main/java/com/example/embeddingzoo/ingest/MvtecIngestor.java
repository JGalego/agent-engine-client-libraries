package com.example.embeddingzoo.ingest;

import com.example.embeddingzoo.Datasets;
import com.example.embeddingzoo.Samples;
import com.example.embeddingzoo.Vectors;
import com.example.embeddingzoo.ZooConfig;
import com.example.embeddingzoo.embed.TextEmbedder;
import com.example.embeddingzoo.signal.ImageAppearance;
import com.example.embeddingzoo.store.StoredRecord;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;

/**
 * Ingests images laid out like MVTec AD.
 *
 * <p>The tool only reads a manifest. {@link #acceptUserDirectory} records that the bytes were
 * placed by the user and that this program did not check them against a publisher checksum.
 */
public final class MvtecIngestor {

  static final Set<String> CATEGORIES = Set.of(
      "bottle", "cable", "capsule", "carpet", "grid", "hazelnut", "leather", "metal_nut",
      "pill", "screw", "tile", "toothbrush", "transistor", "wood", "zipper");

  private MvtecIngestor() {}

  public static Path root(ZooConfig config) {
    return config.dataDir().resolve("mvtec").resolve("images");
  }

  public static CorpusManifest acceptUserDirectory(ZooConfig config, Path directory) throws IOException {
    List<Path> images = images(directory);
    if (images.isEmpty()) {
      throw new IOException("No PNG or JPEG files under " + directory);
    }
    List<CorpusManifest.FileEntry> entries = new ArrayList<>();
    for (Path image : images) {
      String relative = directory.relativize(image).toString().replace('\\', '/');
      entries.add(new CorpusManifest.FileEntry(relative, Vectors.sha256(image), Files.size(image)));
    }
    CorpusManifest manifest = new CorpusManifest(
        Datasets.MVTEC_ID,
        Datasets.MVTEC_NAME,
        Datasets.MVTEC_PAGE,
        "",
        Datasets.MVTEC_LICENSE,
        Datasets.MVTEC_LICENSE_URL,
        Datasets.MVTEC_CITATION,
        Datasets.MVTEC_PUBLISHER,
        false,
        true,
        Instant.now().toString(),
        entries);
    Path dest = config.dataDir().resolve("mvtec").resolve("images");
    if (!directory.toAbsolutePath().normalize().equals(dest.toAbsolutePath().normalize())) {
      throw new IOException("Place the MVTec files in " + dest + " and pass that directory");
    }
    manifest.write(config.dataDir().resolve("mvtec").resolve("manifest.json"));
    return manifest;
  }

  public static List<StoredRecord> load(ZooConfig config, TextEmbedder text) throws IOException {
    Path manifestFile = config.dataDir().resolve("mvtec").resolve("manifest.json");
    if (!Files.isRegularFile(manifestFile)) {
      return List.of();
    }
    CorpusManifest manifest = CorpusManifest.read(manifestFile);
    Path root = root(config);
    List<CorpusManifest.FileEntry> selected = Samples.select(manifest.files, config.maxImages(), false);
    List<StoredRecord> records = new ArrayList<>();
    for (CorpusManifest.FileEntry entry : selected) {
      Path file = root.resolve(entry.path());
      String hash = Vectors.sha256(file);
      if (!hash.equals(entry.sha256())) {
        throw new IOException("MVTec file hash does not match the manifest: " + entry.path());
      }
      BufferedImage image = ImageIO.read(file.toFile());
      if (image == null) {
        throw new IOException("Could not read image " + entry.path());
      }
      Layout layout = layout(entry.path());
      String caption = "MVTec AD directory label. category=" + layout.category
          + " split=" + layout.split + " label=" + layout.label + " file=" + file.getFileName()
          + ". The label is the folder name from the MVTec AD layout. It is not a classifier output "
          + "and it does not describe any other dataset.";
      Map<String, String> metadata = new LinkedHashMap<>();
      metadata.put("category", layout.category);
      metadata.put("split", layout.split);
      metadata.put("label", layout.label);
      metadata.put("layout", layout.recognized ? "mvtec-ad" : "unrecognized");
      metadata.put("publisher", manifest.publisher);
      metadata.put("placed_by_user", Boolean.toString(manifest.placedByUser));
      records.add(StoredRecord.builder()
          .id(Vectors.idFor(manifest.datasetId, entry.path(), text.modelId()))
          .modality("image")
          .datasetId(manifest.datasetId)
          .text(caption)
          .textEmbedding(text.embed(caption))
          .textEmbeddingModel(text.modelId())
          .textEmbeddingArtifact(text.artifact())
          .imageEmbedding(ImageAppearance.embed(image))
          .imageEmbeddingModel(Datasets.IMAGE_MODEL)
          .metadata(metadata)
          .provenance(TelemetryIngestor.provenance(manifest, entry.path(), hash))
          .build());
    }
    return records;
  }

  static List<Path> images(Path root) throws IOException {
    if (!Files.isDirectory(root)) {
      return List.of();
    }
    try (var walk = Files.walk(root)) {
      return walk.filter(Files::isRegularFile)
          .filter(MvtecIngestor::imageFile)
          .filter(path -> !path.toString().replace('\\', '/').contains("ground_truth"))
          .sorted()
          .toList();
    }
  }

  private static boolean imageFile(Path path) {
    String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
    return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".bmp");
  }

  static Layout layout(String relative) {
    String[] parts = relative.replace('\\', '/').split("/");
    if (parts.length >= 4 && CATEGORIES.contains(parts[0]) && ("train".equals(parts[1]) || "test".equals(parts[1]))) {
      return new Layout(parts[0], parts[1], parts[2], true);
    }
    return new Layout("unknown", "unknown", "unknown", false);
  }

  record Layout(String category, String split, String label, boolean recognized) {}
}
