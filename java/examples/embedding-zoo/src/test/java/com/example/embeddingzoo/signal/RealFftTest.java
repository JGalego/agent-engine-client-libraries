package com.example.embeddingzoo.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RealFftTest {

  @Test
  void constantSignalLivesInTheDcBin() {
    double[] real = {1, 1, 1, 1};
    double[] imag = {0, 0, 0, 0};
    RealFft.transform(real, imag);
    assertEquals(4, real[0], 1e-9);
    assertEquals(0, imag[0], 1e-9);
    for (int i = 1; i < real.length; i++) {
      assertEquals(0, real[i], 1e-9);
      assertEquals(0, imag[i], 1e-9);
    }
  }

  @Test
  void nextPowerOfTwo() {
    assertEquals(8, RealFft.nextPow2(5));
    assertEquals(8, RealFft.nextPow2(8));
  }

  @Test
  void sineEnergyIsAtTheExpectedBin() {
    int n = 16;
    double[] real = new double[n];
    double[] imag = new double[n];
    for (int i = 0; i < n; i++) {
      real[i] = Math.sin(2 * Math.PI * i / n);
    }
    RealFft.transform(real, imag);
    double bin1 = Math.hypot(real[1], imag[1]);
    double bin3 = Math.hypot(real[3], imag[3]);
    assertTrue(bin1 > bin3);
  }
}
