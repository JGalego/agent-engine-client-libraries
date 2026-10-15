package com.example.embeddingzoo.ingest;

import com.example.embeddingzoo.Datasets;
import com.example.embeddingzoo.Vectors;
import com.example.embeddingzoo.ZooConfig;
import com.example.embeddingzoo.signal.ImsSignalFile;
import com.github.junrar.Archive;
import com.github.junrar.exception.RarException;
import com.github.junrar.rarfile.FileHeader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;

/**
 * Downloads the NASA IMS zip and extracts test-1 snapshot files.
 *
 * <p>The published zip contains {@code IMS.7z}, which contains RAR archives. This class keeps test
 * 1, the run with eight channels. Tests 2 and 3 stay in the archive unless their snapshot files
 * are already present under the snapshots directory.
 */
public final class ImsAcquisition {

  private static final long MAX_BYTES = 8L << 30;
  private static final Pattern RPM = Pattern.compile("2000\\s*RPM");
  private static final Pattern LOAD = Pattern.compile("6000\\s*lb\\s*s?", Pattern.CASE_INSENSITIVE);

  private ImsAcquisition() {}

  public static Path manifestPath(ZooConfig config) {
    return config.dataDir().resolve("ims").resolve("manifest.json");
  }

  public static Path snapshots(ZooConfig config) {
    return config.dataDir().resolve("ims").resolve("snapshots");
  }

  public static CorpusManifest ensure(ZooConfig config) throws IOException {
    Path manifestFile = manifestPath(config);
    if (Files.isRegularFile(manifestFile)) {
      noteDatasheet(config.dataDir().resolve("ims").resolve("unpack"), config.dataDir().resolve("ims"));
      return CorpusManifest.read(manifestFile);
    }
    Path zip = config.dataDir().resolve("ims").resolve("IMS.zip");
    if (!Files.isRegularFile(zip)) {
      System.err.println("Downloading NASA IMS bearings (about 1 GB)");
      HttpTexts.download(java.net.URI.create(Datasets.IMS_DOWNLOAD), zip);
    }
    Path unpack = config.dataDir().resolve("ims").resolve("unpack");
    Path seven = unpack.resolve("4. Bearings").resolve("IMS.7z");
    if (!Files.isRegularFile(seven)) {
      System.err.println("Unzipping IMS.7z");
      unzip(zip, unpack);
    }
    Path snapshots = snapshots(config);
    Path sevenOut = unpack.resolve("seven");
    if (snapshotFiles(snapshots).isEmpty()) {
      if (!hasRar(sevenOut)) {
        System.err.println("Extracting test 1 from IMS.7z");
        extractSeven(seven, sevenOut);
      }
      Path sevenZip = ensureSevenZip(config);
      if (sevenZip != null) {
        extractWithSevenZip(sevenZip, sevenOut, snapshots);
      } else {
        extractRars(sevenOut, snapshots);
      }
    }
    noteDatasheet(unpack, config.dataDir().resolve("ims"));
    CorpusManifest manifest = manifestFor(zip, snapshots);
    manifest.write(manifestFile);
    System.err.println("IMS manifest lists " + manifest.files.size() + " snapshot files");
    return manifest;
  }

  public static List<Path> snapshotFiles(Path root) throws IOException {
    if (!Files.isDirectory(root)) {
      return List.of();
    }
    try (var walk = Files.walk(root)) {
      return walk.filter(Files::isRegularFile)
          .filter(path -> ImsSignalFile.snapshotName(path.getFileName().toString()))
          .sorted()
          .toList();
    }
  }

  private static void unzip(Path zip, Path dest) throws IOException {
    Files.createDirectories(dest);
    try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
      ZipEntry entry;
      while ((entry = in.getNextEntry()) != null) {
        if (entry.isDirectory()) {
          continue;
        }
        Path target = safe(dest, entry.getName());
        Files.createDirectories(target.getParent());
        copyBounded(in, target);
      }
    }
  }

  private static void extractSeven(Path seven, Path dest) throws IOException {
    Files.createDirectories(dest);
    try (SevenZFile archive = SevenZFile.builder().setFile(seven.toFile()).get()) {
      SevenZArchiveEntry entry;
      byte[] buffer = new byte[1 << 16];
      while ((entry = archive.getNextEntry()) != null) {
        String name = entry.getName().replace('\\', '/');
        boolean keep = keepSevenEntry(name);
        Path target = keep ? safe(dest, name) : null;
        if (target != null) {
          Files.createDirectories(target.getParent());
        }
        try (OutputStream out = target == null ? OutputStream.nullOutputStream() : Files.newOutputStream(target)) {
          int read;
          while ((read = archive.read(buffer)) > 0) {
            out.write(buffer, 0, read);
          }
        }
        if (keep) {
          System.err.println("7z entry " + name);
        }
      }
    }
  }

  private static boolean keepSevenEntry(String name) {
    String lower = name.toLowerCase(Locale.ROOT);
    if (lower.endsWith(".rar")) {
      return lower.contains("1st");
    }
    return lower.endsWith(".txt") || lower.contains("readme");
  }

  private static boolean hasRar(Path dir) throws IOException {
    if (!Files.isDirectory(dir)) {
      return false;
    }
    try (var walk = Files.walk(dir)) {
      return walk.anyMatch(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".rar"));
    }
  }

  static Path ensureSevenZip(ZooConfig config) throws IOException {
    if (!linuxAmd64()) {
      return null;
    }
    Path tools = config.dataDir().resolve("tools");
    Path binary = tools.resolve("7zz");
    if (Files.isRegularFile(binary) && Files.size(binary) > 100_000) {
      binary.toFile().setExecutable(true);
      return binary;
    }
    Path archive = tools.resolve("7z-linux-x64.tar.xz");
    if (!Files.isRegularFile(archive)) {
      System.err.println("Downloading 7-Zip (7zz) to extract the IMS RAR");
      HttpTexts.download(java.net.URI.create(Datasets.SEVEN_ZIP_URL), archive);
    }
    Files.createDirectories(tools);
    try (InputStream file = Files.newInputStream(archive);
        XZCompressorInputStream xz = new XZCompressorInputStream(file);
        TarArchiveInputStream tar = new TarArchiveInputStream(xz)) {
      TarArchiveEntry entry;
      while ((entry = tar.getNextEntry()) != null) {
        String name = entry.getName();
        if (entry.isDirectory()) {
          continue;
        }
        if ("7zz".equals(name) || name.endsWith("/7zz")) {
          copyBounded(tar, binary);
        } else if ("License.txt".equals(name) || name.endsWith("/License.txt")) {
          copyBounded(tar, tools.resolve("7zip-License.txt"));
        }
      }
    }
    if (!Files.isRegularFile(binary)) {
      throw new IOException("7zz was not in " + archive);
    }
    binary.toFile().setExecutable(true);
    return binary;
  }

  private static boolean linuxAmd64() {
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
    return os.contains("linux") && (arch.contains("amd64") || arch.contains("x86_64"));
  }

  private static void extractWithSevenZip(Path sevenZip, Path sevenDir, Path dest) throws IOException {
    List<Path> rars;
    try (var walk = Files.walk(sevenDir)) {
      rars = walk.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".rar")).toList();
    }
    if (rars.isEmpty()) {
      throw new IOException("No RAR archives under " + sevenDir);
    }
    Files.createDirectories(dest);
    for (Path rar : rars) {
      System.err.println("Extracting " + rar.getFileName() + " with 7zz");
      Process process = new ProcessBuilder(
              sevenZip.toString(), "x", "-y", "-bd", "-o" + dest.toAbsolutePath(), rar.toAbsolutePath().toString())
          .redirectErrorStream(true)
          .redirectOutput(ProcessBuilder.Redirect.INHERIT)
          .start();
      int code;
      try {
        code = process.waitFor();
      } catch (InterruptedException e) {
        process.destroyForcibly();
        Thread.currentThread().interrupt();
        throw new IOException("7zz was interrupted while reading " + rar, e);
      }
      if (code > 1) {
        throw new IOException("7zz exited " + code + " while reading " + rar);
      }
    }
  }

  private static void extractRars(Path sevenDir, Path snapshots) throws IOException {
    if (!Files.isDirectory(sevenDir)) {
      return;
    }
    List<Path> rars;
    try (var walk = Files.walk(sevenDir)) {
      rars = walk.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".rar")).toList();
    }
    for (Path rar : rars) {
      System.err.println("Extracting snapshots from " + rar.getFileName());
      extractRar(rar, snapshots);
    }
  }

  private static void extractRar(Path rar, Path dest) throws IOException {
    Files.createDirectories(dest);
    int kept = 0;
    Path current = null;
    try (Archive archive = new Archive(rar.toFile())) {
      FileHeader header;
      while ((header = archive.nextFileHeader()) != null) {
        if (header.isDirectory()) {
          continue;
        }
        String name = header.getFileName().replace('\\', '/');
        String file = name.substring(name.lastIndexOf('/') + 1);
        boolean keep = ImsSignalFile.snapshotName(file) || file.toLowerCase(Locale.ROOT).contains("readme");
        Path target = keep ? safe(dest, name) : null;
        if (target != null) {
          Files.createDirectories(target.getParent());
        }
        // Solid RAR entries have to be decoded in order. Skipping extractFile desynchronizes the stream.
        current = target;
        try (OutputStream out = target == null ? OutputStream.nullOutputStream() : Files.newOutputStream(target)) {
          archive.extractFile(header, out);
        }
        current = null;
        if (keep) {
          kept++;
          if (kept % 200 == 0) {
            System.err.println("Extracted " + kept + " snapshot files");
          }
        }
      }
    } catch (RarException e) {
      if (current != null) {
        Files.deleteIfExists(current);
      }
      int written = snapshotFiles(dest).size();
      if (written == 0) {
        throw new IOException("Could not read " + rar + ". The archive may be a RAR this extractor cannot stream.", e);
      }
      System.err.println(
          "junrar stopped after " + written + " snapshot files (" + e.getClass().getSimpleName()
              + "). Install 7zz or re-run so the demo can download it. The files already written are kept.");
    }
    System.err.println("Kept " + kept + " files from " + rar.getFileName());
  }

  private static void noteDatasheet(Path unpack, Path imsDir) throws IOException {
    Path facts = imsDir.resolve("datasheet-facts.json");
    if (Files.isRegularFile(facts) || !Files.isDirectory(unpack)) {
      return;
    }
    StringBuilder text = new StringBuilder();
    try (var walk = Files.walk(unpack)) {
      List<Path> docs = walk.filter(Files::isRegularFile).filter(ImsAcquisition::smallText).toList();
      for (Path doc : docs) {
        text.append(new String(Files.readAllBytes(doc), java.nio.charset.StandardCharsets.ISO_8859_1)).append('\n');
      }
    }
    try (var walk = Files.walk(unpack)) {
      List<Path> pdfs = walk.filter(Files::isRegularFile).filter(ImsAcquisition::readmePdf).toList();
      for (Path pdf : pdfs) {
        text.append(pdfText(pdf)).append('\n');
      }
    }
    String body = text.toString();
    var node = com.mongodb.agentengine.sdk.Json.mapper().createObjectNode();
    if (RPM.matcher(body).find()) {
      node.put("shaft_speed_rpm", "2000");
    }
    if (LOAD.matcher(body).find()) {
      node.put("radial_load_lbs", "6000");
    }
    if (!node.isEmpty()) {
      node.put("source", "text packaged in the NASA IMS archive");
      com.mongodb.agentengine.sdk.Json.mapper().writerWithDefaultPrettyPrinter().writeValue(facts.toFile(), node);
    }
  }

  private static boolean readmePdf(Path path) {
    String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
    try {
      return lower.endsWith(".pdf") && lower.contains("readme") && Files.size(path) < 5_000_000;
    } catch (IOException e) {
      return false;
    }
  }

  private static String pdfText(Path pdf) {
    Path tmp = null;
    try {
      tmp = Files.createTempFile("ims-readme-", ".txt");
      Process process = new ProcessBuilder("pdftotext", "-layout", pdf.toString(), tmp.toString())
          .redirectErrorStream(true)
          .start();
      if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
        process.destroyForcibly();
        return "";
      }
      return Files.readString(tmp);
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      return "";
    } finally {
      if (tmp != null) {
        try {
          Files.deleteIfExists(tmp);
        } catch (IOException ignored) {
          // A leftover temp file does not change the recorded facts.
        }
      }
    }
  }

  private static boolean smallText(Path path) {
    String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
    if (lower.endsWith(".pdf") || lower.endsWith(".rar") || lower.endsWith(".zip") || lower.endsWith(".7z")
        || lower.endsWith(".png") || lower.endsWith(".jpg")) {
      return false;
    }
    try {
      return (lower.endsWith(".txt") || lower.endsWith(".md") || lower.contains("readme")) && Files.size(path) < 1_000_000;
    } catch (IOException e) {
      return false;
    }
  }

  private static CorpusManifest manifestFor(Path zip, Path snapshots) throws IOException {
    String zipSha = Vectors.sha256(zip);
    Files.writeString(zip.resolveSibling("zip.sha256"), zipSha + "  IMS.zip\n");
    List<Path> files = snapshotFiles(snapshots);
    List<CorpusManifest.FileEntry> entries = new ArrayList<>();
    for (Path file : files) {
      String relative = snapshots.relativize(file).toString().replace('\\', '/');
      entries.add(new CorpusManifest.FileEntry(relative, Vectors.sha256(file), Files.size(file)));
    }
    entries.sort(Comparator.comparing(CorpusManifest.FileEntry::path));
    return new CorpusManifest(
        Datasets.IMS_ID,
        Datasets.IMS_NAME,
        Datasets.IMS_PAGE,
        Datasets.IMS_DOWNLOAD,
        Datasets.IMS_LICENSE,
        Datasets.IMS_LICENSE_URL,
        Datasets.IMS_CITATION,
        Datasets.IMS_PUBLISHER,
        false,
        false,
        Instant.now().toString(),
        entries);
  }

  private static Path safe(Path root, String name) throws IOException {
    Path target = root.resolve(name.replace('\\', '/')).normalize();
    if (!target.startsWith(root)) {
      throw new IOException("Refusing to write outside " + root + ": " + name);
    }
    return target;
  }

  private static void copyBounded(InputStream in, Path target) throws IOException {
    long written = 0;
    try (OutputStream out = Files.newOutputStream(target)) {
      byte[] buffer = new byte[1 << 16];
      int read;
      while ((read = in.read(buffer)) > 0) {
        written += read;
        if (written > MAX_BYTES) {
          throw new IOException("Archive entry exceeds " + MAX_BYTES + " bytes: " + target.getFileName());
        }
        out.write(buffer, 0, read);
      }
    }
  }
}
