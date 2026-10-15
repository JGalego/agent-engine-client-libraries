package com.example.embeddingzoo.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class VibrationAndSignalTest {

  @Test
  void unitSineHasTheExpectedRms() {
    double[] samples = new double[2048];
    for (int i = 0; i < samples.length; i++) {
      samples[i] = Math.sin(2 * Math.PI * i / 32);
    }
    VibrationFeatures.Summary summary = VibrationFeatures.summarize(samples);
    assertEquals(1 / Math.sqrt(2), summary.rms(), 0.02);
    assertEquals(12, summary.embedding().size());
    double norm = 0;
    for (double value : summary.embedding()) {
      norm += value * value;
    }
    assertEquals(1, Math.sqrt(norm), 1e-6);
  }

  @Test
  void parserReadsARectangularGrid() throws Exception {
    Path file = Files.createTempFile("ims", ".txt");
    Files.writeString(file, "-0.5 0.25\n0.5 -0.25\n");
    double[][] channels = ImsSignalFile.readChannels(file);
    assertEquals(2, channels.length);
    assertEquals(-0.5, channels[0][0], 1e-9);
    assertEquals(0.25, channels[1][0], 1e-9);
    assertEquals(0.5, channels[0][1], 1e-9);
    assertEquals("2003-10-22T12:06:24", ImsSignalFile.timestampLabel("2003.10.22.12.06.24").orElseThrow());
  }

  @Test
  void testOneChannelMapFollowsTheDataSheet() {
    assertEquals(3, ImsLayout.bearing(8, 4));
    assertEquals(4, ImsLayout.representativeChannel(8, 3));
    assertEquals("inner race defect at the end of test 1", ImsLayout.testEndNote("1st_test/2003.10.22.12.06.24", 8, 3));
    assertEquals("", ImsLayout.testEndNote("1st_test/2003.10.22.12.06.24", 8, 1));
    assertTrue(ImsLayout.testEndNote("1st_test/x", 8, 3).contains("end of test"));
  }
}
