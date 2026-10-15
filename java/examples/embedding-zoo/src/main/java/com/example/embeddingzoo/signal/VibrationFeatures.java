package com.example.embeddingzoo.signal;

import com.example.embeddingzoo.Vectors;
import java.util.Arrays;
import java.util.List;

/**
 * Classical vibration features for one IMS channel.
 *
 * <p>RMS is computed on the recorded samples. Kurtosis, skewness, crest factor, and the spectrum
 * are computed after subtracting the mean. The documented sample rate is 20 kHz; the files do not
 * store a sample-rate field.
 */
public final class VibrationFeatures {

  public static final int DIMENSIONS = 12;
  private static final double[] BAND_EDGES_HZ = {0, 1_000, 2_000, 4_000, 8_000, 10_000};

  private VibrationFeatures() {}

  public record Summary(
      int sampleCount,
      double mean,
      double rms,
      double standardDeviation,
      double standardizedKurtosis,
      double skewness,
      double crestFactor,
      double peakToPeak,
      double spectralCentroidHz,
      double[] bandEnergyFractions,
      List<Double> embedding) {}

  public static Summary summarize(double[] samples) {
    if (samples.length == 0) {
      throw new IllegalArgumentException("A vibration channel has no samples");
    }
    double mean = 0;
    double square = 0;
    for (double sample : samples) {
      mean += sample;
      square += sample * sample;
    }
    mean /= samples.length;
    double rms = Math.sqrt(square / samples.length);
    double[] centered = new double[samples.length];
    double m2 = 0;
    double m3 = 0;
    double m4 = 0;
    double min = Double.POSITIVE_INFINITY;
    double max = Double.NEGATIVE_INFINITY;
    for (int i = 0; i < samples.length; i++) {
      double value = samples[i] - mean;
      centered[i] = value;
      m2 += value * value;
      m3 += value * value * value;
      m4 += value * value * value * value;
      min = Math.min(min, value);
      max = Math.max(max, value);
    }
    m2 /= samples.length;
    m3 /= samples.length;
    m4 /= samples.length;
    double std = Math.sqrt(m2);
    double kurtosis = m2 < 1e-18 ? 0 : m4 / (m2 * m2);
    double skewness = m2 < 1e-18 ? 0 : m3 / Math.pow(m2, 1.5);
    double crest = std < 1e-18 ? 0 : Math.max(Math.abs(min), Math.abs(max)) / std;
    double peakToPeak = max - min;
    Spectrum spectrum = spectrum(centered, 20_000);
    double[] raw = new double[DIMENSIONS];
    raw[0] = Math.log1p(Math.abs(rms));
    raw[1] = kurtosis / 10.0;
    raw[2] = skewness;
    raw[3] = crest / 10.0;
    raw[4] = Math.log1p(Math.max(0, peakToPeak));
    raw[5] = Math.log1p(std);
    raw[6] = spectrum.centroidHz / 10_000.0;
    System.arraycopy(spectrum.bands, 0, raw, 7, 5);
    return new Summary(
        samples.length,
        mean,
        rms,
        std,
        kurtosis,
        skewness,
        crest,
        peakToPeak,
        spectrum.centroidHz,
        spectrum.bands,
        Vectors.normalize(raw));
  }

  /** Max-absolute bucket downsample, for plotting a long recording in a fixed number of pixels. */
  public static double[] downsampleMaxAbs(double[] samples, int buckets) {
    if (samples.length == 0 || buckets <= 0) {
      return new double[0];
    }
    if (samples.length <= buckets) {
      return Arrays.copyOf(samples, samples.length);
    }
    double[] out = new double[buckets];
    for (int bucket = 0; bucket < buckets; bucket++) {
      int start = (int) ((long) bucket * samples.length / buckets);
      int end = (int) ((long) (bucket + 1) * samples.length / buckets);
      double extreme = 0;
      for (int i = start; i < end; i++) {
        if (Math.abs(samples[i]) > Math.abs(extreme)) {
          extreme = samples[i];
        }
      }
      out[bucket] = extreme;
    }
    return out;
  }

  /** Positive-frequency magnitude, averaged into {@code bars} bins. */
  public static double[] spectrumBars(double[] samples, int bars) {
    double mean = 0;
    for (double sample : samples) {
      mean += sample;
    }
    mean /= Math.max(1, samples.length);
    double[] centered = new double[samples.length];
    for (int i = 0; i < samples.length; i++) {
      centered[i] = samples[i] - mean;
    }
    Spectrum spectrum = spectrum(centered, 20_000);
    if (bars <= 0 || spectrum.magnitudes.length == 0) {
      return new double[0];
    }
    double[] out = new double[bars];
    for (int bar = 0; bar < bars; bar++) {
      int start = (int) ((long) bar * spectrum.magnitudes.length / bars);
      int end = Math.max(start + 1, (int) ((long) (bar + 1) * spectrum.magnitudes.length / bars));
      double sum = 0;
      int count = 0;
      for (int i = start; i < end && i < spectrum.magnitudes.length; i++) {
        sum += spectrum.magnitudes[i];
        count++;
      }
      out[bar] = count == 0 ? 0 : sum / count;
    }
    return out;
  }

  private record Spectrum(double centroidHz, double[] bands, double[] magnitudes) {}

  private static Spectrum spectrum(double[] centered, double sampleRateHz) {
    int used = Math.min(centered.length, 32_768);
    int fftSize = RealFft.nextPow2(used);
    double[] real = new double[fftSize];
    double[] imag = new double[fftSize];
    for (int i = 0; i < used; i++) {
      double window = used == 1 ? 1 : 0.5 * (1 - Math.cos(2 * Math.PI * i / (used - 1)));
      real[i] = centered[i] * window;
    }
    RealFft.transform(real, imag);
    int bins = fftSize / 2;
    double[] magnitudes = new double[bins];
    double powerSum = 0;
    double weighted = 0;
    double[] bandPower = new double[5];
    for (int k = 1; k < bins; k++) {
      double magnitude = Math.hypot(real[k], imag[k]);
      magnitudes[k] = magnitude;
      double power = magnitude * magnitude;
      double frequency = k * sampleRateHz / fftSize;
      powerSum += power;
      weighted += frequency * power;
      int band = bandIndex(frequency);
      if (band >= 0) {
        bandPower[band] += power;
      }
    }
    double[] fractions = new double[5];
    if (powerSum > 0) {
      for (int i = 0; i < fractions.length; i++) {
        fractions[i] = bandPower[i] / powerSum;
      }
    }
    double centroid = powerSum == 0 ? 0 : weighted / powerSum;
    return new Spectrum(centroid, fractions, magnitudes);
  }

  private static int bandIndex(double frequencyHz) {
    for (int i = 0; i < BAND_EDGES_HZ.length - 1; i++) {
      if (frequencyHz >= BAND_EDGES_HZ[i] && frequencyHz < BAND_EDGES_HZ[i + 1]) {
        return i;
      }
    }
    return -1;
  }
}
