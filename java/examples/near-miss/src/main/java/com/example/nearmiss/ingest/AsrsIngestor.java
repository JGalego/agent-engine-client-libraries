package com.example.nearmiss.ingest;

import com.example.nearmiss.Datasets;
import com.example.nearmiss.Vectors;
import com.example.nearmiss.NearMissConfig;
import com.example.nearmiss.embed.TextEmbedder;
import com.example.nearmiss.store.StoredRecord;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * Reads aviation safety reports from a few of the ASRS "report sets", the curated PDFs that NASA
 * publishes for topics such as icing or fuel problems.
 *
 * <p>Each PDF lists its reports twice: a short synopsis for each, then the full records. A record has
 * a few labelled fields, one or more reporter narratives, and a synopsis. The PDFs are cached under
 * {@code data/cache/asrs}.
 */
public final class AsrsIngestor {

  /** Report sets about failures and hazards, to sit beside the device and vehicle reports. */
  static final List<String> REPORT_SETS = List.of("icing", "fuel", "cabin_fumes", "upsets", "mechanic", "gps");

  private static final Pattern RECORD = Pattern.compile("(?m)^ACN: (\\d+) \\((\\d+) of (\\d+)\\)\\s*$");
  private static final Pattern FIELD = Pattern.compile("^([A-Za-z][A-Za-z /.()-]*?) : (.+)$");
  private static final int MIN_NARRATIVE_CHARS = 80;

  /** One report as it appears in a report set. */
  record Report(String acn, String date, String aircraft, String flightPhase, String narrative, String synopsis) {}

  private AsrsIngestor() {}

  public static List<StoredRecord> load(NearMissConfig config, TextEmbedder text) throws IOException {
    List<StoredRecord> records = new ArrayList<>();
    for (String set : REPORT_SETS) {
      URI url = URI.create(Datasets.ASRS_REPORT_SET_URL.formatted(set));
      String pdf = pdfText(config, set, url);
      String title = title(pdf, set);
      int kept = 0;
      for (Report report : parse(pdf)) {
        if (kept == config.recordsPerQuery()) {
          break;
        }
        kept += add(report, title, url, text, records);
      }
      System.err.println("ASRS " + title + ": " + kept + " reports");
    }
    return records;
  }

  /** The reports of one set, with narratives. Reports that only appear in the opening synopsis list are dropped. */
  static List<Report> parse(String pdfText) {
    Map<String, Report> byAcn = new LinkedHashMap<>();
    Matcher matcher = RECORD.matcher(pdfText);
    List<int[]> starts = new ArrayList<>();
    List<String> acns = new ArrayList<>();
    while (matcher.find()) {
      starts.add(new int[] {matcher.start(), matcher.end()});
      acns.add(matcher.group(1));
    }
    for (int i = 0; i < starts.size(); i++) {
      int end = i + 1 < starts.size() ? starts.get(i + 1)[0] : pdfText.length();
      Report report = block(acns.get(i), pdfText.substring(starts.get(i)[1], end));
      if (report.narrative().length() >= MIN_NARRATIVE_CHARS) {
        byAcn.put(report.acn(), report);
      }
    }
    return List.copyOf(byAcn.values());
  }

  private static Report block(String acn, String body) {
    String date = "";
    String aircraft = "";
    String phase = "";
    StringBuilder narrative = new StringBuilder();
    StringBuilder synopsis = new StringBuilder();
    StringBuilder open = null;
    for (String raw : body.split("\\R")) {
      String line = raw.strip();
      if (line.isEmpty() || line.matches("\\d{1,3}")) {
        continue;
      }
      if (line.matches("Narrative: 1")) {
        open = narrative;
        continue;
      }
      if (line.matches("Narrative: [2-9]") || line.equals("Synopsis")) {
        open = line.equals("Synopsis") ? synopsis : null;
        continue;
      }
      if (open != null) {
        open.append(open.length() == 0 ? "" : " ").append(line);
        continue;
      }
      Matcher field = FIELD.matcher(line);
      if (field.matches()) {
        String key = field.group(1).strip();
        String value = field.group(2).strip();
        if (key.equals("Date") && date.isEmpty()) {
          date = value;
        } else if (key.equals("Make Model Name") && aircraft.isEmpty()) {
          aircraft = value;
        } else if (key.equals("Flight Phase") && phase.isEmpty()) {
          phase = value;
        }
      }
    }
    return new Report(acn, date, aircraft, phase, narrative.toString(), synopsis.toString());
  }

  /** The report set's own title, printed on its first page under "ASRS Database Report Set". */
  static String title(String pdfText, String fallback) {
    String[] lines = pdfText.split("\\R");
    for (int i = 0; i < lines.length - 1; i++) {
      if (lines[i].strip().equals("ASRS Database Report Set")) {
        for (int j = i + 1; j < lines.length && j < i + 5; j++) {
          if (!lines[j].isBlank()) {
            return lines[j].strip();
          }
        }
      }
    }
    return fallback;
  }

  static int add(Report report, String setTitle, URI setUrl, TextEmbedder text, List<StoredRecord> out) {
    String full = (report.synopsis().isEmpty() ? "" : report.synopsis() + " ") + report.narrative();
    String label = report.aircraft().isEmpty() ? "Aircraft" : report.aircraft();
    List<String> chunks = TextChunks.split(full, 120, 30, 2);
    String documentUrl = setUrl + "#ACN-" + report.acn();
    for (int i = 0; i < chunks.size(); i++) {
      String body = label + ", " + (report.flightPhase().isEmpty() ? setTitle : report.flightPhase()) + ". " + chunks.get(i);
      String locator = "ACN-" + report.acn() + "#chunk-" + i;
      Map<String, String> metadata = new LinkedHashMap<>();
      metadata.put("document_url", documentUrl);
      metadata.put("acn", report.acn());
      metadata.put("report_set", setTitle);
      metadata.put("date", report.date());
      metadata.put("aircraft", report.aircraft());
      metadata.put("flight_phase", report.flightPhase());
      metadata.put("chunk", Integer.toString(i));
      metadata.put("chunk_count", Integer.toString(chunks.size()));
      metadata.put("publisher", Datasets.ASRS_PUBLISHER);
      out.add(StoredRecord.builder()
          .id(Vectors.idFor(Datasets.ASRS_ID, locator, text.modelId()))
          .kind(Datasets.KIND_AVIATION)
          .datasetId(Datasets.ASRS_ID)
          .text(body)
          .embedding(text.embed(body))
          .embeddingModel(text.modelId())
          .embeddingArtifact(text.artifact())
          .metadata(metadata)
          .provenance(Provenance.of(
              Datasets.ASRS_NAME + ": " + setTitle, setUrl.toString(), Datasets.ASRS_LICENSE,
              Datasets.ASRS_LICENSE_URL, Datasets.ASRS_CITATION, locator, Vectors.sha256(full)))
          .build());
    }
    return 1;
  }

  private static String pdfText(NearMissConfig config, String set, URI url) throws IOException {
    Path pdf = config.dataDir().resolve("cache").resolve("asrs").resolve(set + ".pdf");
    if (!Files.isRegularFile(pdf)) {
      HttpTexts.download(url, pdf);
    }
    try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
      return new PDFTextStripper().getText(document);
    }
  }
}
