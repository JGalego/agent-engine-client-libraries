package com.example.embeddingzoo.signal;

/** In-place radix-2 Cooley–Tukey FFT. {@code real} and {@code imag} must share a power-of-two length. */
public final class RealFft {

  private RealFft() {}

  public static void transform(double[] real, double[] imag) {
    int n = real.length;
    if (imag.length != n || n == 0 || (n & (n - 1)) != 0) {
      throw new IllegalArgumentException("FFT length must be a power of two");
    }
    for (int i = 1, j = 0; i < n; i++) {
      int bit = n >>> 1;
      for (; (j & bit) != 0; bit >>>= 1) {
        j ^= bit;
      }
      j ^= bit;
      if (i < j) {
        swap(real, i, j);
        swap(imag, i, j);
      }
    }
    for (int length = 2; length <= n; length <<= 1) {
      double angle = -2.0 * Math.PI / length;
      double wLenReal = Math.cos(angle);
      double wLenImag = Math.sin(angle);
      for (int start = 0; start < n; start += length) {
        double wReal = 1;
        double wImag = 0;
        for (int k = 0; k < length / 2; k++) {
          int even = start + k;
          int odd = even + length / 2;
          double vReal = real[odd] * wReal - imag[odd] * wImag;
          double vImag = real[odd] * wImag + imag[odd] * wReal;
          real[odd] = real[even] - vReal;
          imag[odd] = imag[even] - vImag;
          real[even] += vReal;
          imag[even] += vImag;
          double nextReal = wReal * wLenReal - wImag * wLenImag;
          wImag = wReal * wLenImag + wImag * wLenReal;
          wReal = nextReal;
        }
      }
    }
  }

  public static int nextPow2(int n) {
    int p = 1;
    while (p < n) {
      p <<= 1;
    }
    return p;
  }

  private static void swap(double[] values, int i, int j) {
    double tmp = values[i];
    values[i] = values[j];
    values[j] = tmp;
  }
}
