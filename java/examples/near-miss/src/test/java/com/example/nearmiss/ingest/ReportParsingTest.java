package com.example.nearmiss.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.nearmiss.Datasets;
import com.example.nearmiss.embed.TextEmbedder;
import com.example.nearmiss.store.StoredRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.agentengine.sdk.Json;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReportParsingTest {

  private static final String MAUDE_REPORT = """
      {"report_number": "2032227-2024-00363", "event_type": "Injury", "date_received": "20240221",
       "patient": [{"patient_age": "71", "patient_sex": "F"}],
       "device": [{"generic_name": "INFUSION PUMP", "brand_name": "ACME PUMP 9", "manufacturer_d_name": "ACME MEDICAL",
                   "device_report_product_code": "FRN"}],
       "mdr_text": [
         {"text_type_code": "Additional Manufacturer Narrative", "text": "Evaluation is pending."},
         {"text_type_code": "Description of Event or Problem",
          "text": "The pump alarmed and stopped infusing. The nurse restarted it and the occlusion alarm sounded again twice."}]}
      """;

  @Test
  void splitKeepsShortTextWhole() {
    assertEquals(List.of("one two three"), TextChunks.split("  one   two three ", 10, 2, 3));
  }

  @Test
  void splitOverlapsWindowsAndStopsAtTheCap() {
    String text = String.join(" ", java.util.stream.IntStream.range(0, 100).mapToObj(Integer::toString).toList());
    List<String> chunks = TextChunks.split(text, 40, 10, 3);
    assertEquals(3, chunks.size());
    assertTrue(chunks.get(0).endsWith("39"));
    assertTrue(chunks.get(1).startsWith("30 "));
    assertEquals(List.of(), TextChunks.split("   ", 40, 10, 3));
  }

  @Test
  void maudeKeepsTheEventNarrativeNotTheManufacturerNote() throws Exception {
    JsonNode report = Json.mapper().readTree(MAUDE_REPORT);
    assertTrue(MaudeIngestor.narrative(report).startsWith("The pump alarmed"));
  }

  @Test
  void maudeRecordCarriesProvenanceAndNoPatientFields() throws Exception {
    List<StoredRecord> out = new ArrayList<>();
    int added = MaudeIngestor.add(Json.mapper().readTree(MAUDE_REPORT), new FixedEmbedder(), out);
    assertEquals(1, added);
    StoredRecord record = out.get(0);
    assertEquals(Datasets.KIND_DEVICE, record.kind());
    assertEquals("FRN", record.metadata().get("product_code"));
    assertEquals(Datasets.MAUDE_LICENSE, record.provenance().get("license"));
    assertTrue(record.provenance().get("source_url").contains("2032227-2024-00363"));
    assertFalse(record.metadata().containsKey("patient_age"));
    assertFalse(record.metadata().toString().contains("71"));
  }

  @Test
  void maudeSkipsReportsWithoutAUsableNarrative() throws Exception {
    JsonNode report = Json.mapper().readTree("""
        {"report_number": "1", "event_type": "Death", "device": [{}], "mdr_text": [{"text": "N/A"}]}
        """);
    assertEquals(0, MaudeIngestor.add(report, new FixedEmbedder(), new ArrayList<>()));
  }

  @Test
  void nhtsaRanksCrashesAndInjuriesAheadOfTheRest() throws Exception {
    JsonNode quiet = Json.mapper().readTree("{\"crash\": false, \"fire\": false, \"numberOfInjuries\": 0, \"numberOfDeaths\": 0}");
    JsonNode crash = Json.mapper().readTree("{\"crash\": true, \"fire\": true, \"numberOfInjuries\": 2, \"numberOfDeaths\": 0}");
    assertTrue(NhtsaIngestor.severity(crash) > NhtsaIngestor.severity(quiet));
  }

  @Test
  void nhtsaRecordDropsTheVin() throws Exception {
    JsonNode complaint = Json.mapper().readTree("""
        {"odiNumber": 11762157, "vin": "4T1BF1FK4FU", "crash": false, "fire": false, "numberOfInjuries": 0,
         "numberOfDeaths": 0, "dateComplaintFiled": "09/04/2026", "components": "ELECTRICAL SYSTEM",
         "summary": "The contact owns a 2015 Toyota Camry. While driving 65 mph the dashboard lights flickered and the engine lost power."}
        """);
    List<StoredRecord> out = new ArrayList<>();
    NhtsaIngestor.Vehicle vehicle = new NhtsaIngestor.Vehicle("TOYOTA", "CAMRY", 2015);
    int added = NhtsaIngestor.add(vehicle, complaint, NhtsaIngestor.query(vehicle), new FixedEmbedder(), out);
    assertEquals(1, added);
    StoredRecord record = out.get(0);
    assertEquals(Datasets.KIND_VEHICLE, record.kind());
    assertEquals("TOYOTA CAMRY 2015", record.metadata().get("vehicle"));
    assertFalse(record.metadata().toString().contains("4T1BF1FK4FU"));
    assertFalse(record.provenance().toString().contains("4T1BF1FK4FU"));
  }

  private static final class FixedEmbedder implements TextEmbedder {
    @Override public String modelId() { return "test/fixed"; }
    @Override public String artifact() { return "test"; }
    @Override public int dimensions() { return 2; }
    @Override public List<Double> embed(String text) { return List.of(1.0, 0.0); }
  }
}
