package com.example.nearmiss.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.nearmiss.Datasets;
import com.example.nearmiss.embed.TextEmbedder;
import com.example.nearmiss.store.StoredRecord;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AsrsParsingTest {

  /** Shaped like a report set PDF: a synopsis list first, then the full records. */
  private static final String PDF = """
      ASRS Database Report Set

      Commuter and GA Icing Incidents

      Number of Records in Report Set ........................50

      ACN: 2041959 (1 of 2)

      Synopsis
       An experimental aircraft pilot reported turbulence and icing.

      ACN: 2039482 (2 of 2)

      Synopsis
       A Cessna 172 instructor reported an altitude deviation while avoiding icing.

      Report Narratives
      ACN: 2041959 (1 of 2)

      Time / Day
       Date : 202310

      Aircraft
       Reference : X
       Make Model Name : Experimental Aircraft
       Flight Phase : Cruise

      Aircraft
       Reference : Y
       Make Model Name : Skyhawk 172/Cutlass 172

      Person
       ASRS Report Number.Accession Number : 2041959

      Narrative: 1
       I checked the weather several times before departure. I was IMC when I encountered ice and a lot
       of turbulence between 15 and 20,000 ft. My autopilot kicked off and I had a hard time holding altitude.
      12
       I got the plane back under control at about 9000 ft.

      Narrative: 2
       A second reporter's account that is not used.

      Synopsis
       An experimental aircraft pilot reported turbulence and icing.
      ACN: 2039482 (2 of 2)

      Aircraft
       Make Model Name : Skyhawk 172/Cutlass 172
       Flight Phase : Cruise

      Narrative: 1
       Too short.

      Synopsis
       A Cessna 172 instructor reported an altitude deviation while avoiding icing.
      """;

  @Test
  void keepsRecordsWithANarrativeAndDropsTheSynopsisOnlyList() {
    List<AsrsIngestor.Report> reports = AsrsIngestor.parse(PDF);
    assertEquals(1, reports.size());
    AsrsIngestor.Report report = reports.get(0);
    assertEquals("2041959", report.acn());
    assertEquals("202310", report.date());
    assertEquals("Experimental Aircraft", report.aircraft());
    assertEquals("Cruise", report.flightPhase());
  }

  @Test
  void usesTheFirstNarrativeAndSkipsPageNumbers() {
    AsrsIngestor.Report report = AsrsIngestor.parse(PDF).get(0);
    assertTrue(report.narrative().startsWith("I checked the weather"));
    assertTrue(report.narrative().contains("holding altitude. I got the plane back"));
    assertFalse(report.narrative().contains("second reporter"));
    assertEquals("An experimental aircraft pilot reported turbulence and icing.", report.synopsis());
  }

  @Test
  void readsTheReportSetTitle() {
    assertEquals("Commuter and GA Icing Incidents", AsrsIngestor.title(PDF, "icing"));
    assertEquals("icing", AsrsIngestor.title("no heading", "icing"));
  }

  @Test
  void recordKeepsTheReportSetAndAircraftForGrouping() {
    List<StoredRecord> out = new ArrayList<>();
    URI url = URI.create("https://asrs.arc.nasa.gov/docs/rpsts/icing.pdf");
    AsrsIngestor.add(AsrsIngestor.parse(PDF).get(0), "Commuter and GA Icing Incidents", url, new Fixed(), out);
    StoredRecord record = out.get(0);
    assertEquals(Datasets.KIND_AVIATION, record.kind());
    assertEquals("Experimental Aircraft", record.metadata().get("aircraft"));
    assertEquals("Commuter and GA Icing Incidents", record.metadata().get("report_set"));
    assertTrue(record.metadata().get("document_url").endsWith("icing.pdf#ACN-2041959"));
    assertTrue(record.text().startsWith("Experimental Aircraft, Cruise."));
    assertEquals(Datasets.ASRS_LICENSE, record.provenance().get("license"));
  }

  private static final class Fixed implements TextEmbedder {
    @Override public String modelId() { return "test/fixed"; }
    @Override public String artifact() { return "test"; }
    @Override public int dimensions() { return 2; }
    @Override public List<Double> embed(String text) { return List.of(1.0, 0.0); }
  }
}
