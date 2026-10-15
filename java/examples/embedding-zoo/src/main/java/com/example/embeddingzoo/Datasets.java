package com.example.embeddingzoo;

/**
 * Licenses, citations, and source URLs for the corpora this demo ingests.
 *
 * <p>The strings here are bibliographic. They are copied onto each stored record. They are not a
 * substitute for the dataset owners' terms.
 */
public final class Datasets {

  public static final String TEXT_MODEL = "sentence-transformers/all-MiniLM-L6-v2";
  public static final String TEXT_ARTIFACT = "Xenova/all-MiniLM-L6-v2/onnx/model.onnx";
  public static final String TEXT_LICENSE = "Apache-2.0";
  public static final String TEXT_LICENSE_URL = "https://www.apache.org/licenses/LICENSE-2.0.txt";
  public static final String VOCAB_URL =
      "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/vocab.txt";
  public static final String ONNX_URL =
      "https://huggingface.co/Xenova/all-MiniLM-L6-v2/resolve/main/onnx/model.onnx";

  public static final String IMS_ID = "nasa-ims-bearings";
  public static final String IMS_NAME = "NASA IMS bearings";
  public static final String IMS_PAGE = "https://data.nasa.gov/dataset/ims-bearings";
  public static final String IMS_DOWNLOAD = "https://phm-datasets.s3.amazonaws.com/NASA/4.+Bearings.zip";
  /** Linux x64 7zz. junrar reports a CRC error before the end of the solid test-1 RAR. */
  public static final String SEVEN_ZIP_URL = "https://www.7-zip.org/a/7z2602-linux-x64.tar.xz";
  public static final String SEVEN_ZIP_LICENSE = "GNU LGPL with the 7-Zip unRAR restriction, plus BSD for some files";
  public static final String IMS_LICENSE =
      "NASA Open Data Portal lists this record as other-license-specified and links U.S. Government Works";
  public static final String IMS_LICENSE_URL = "https://www.usa.gov/government-works";
  public static final String IMS_CITATION =
      "J. Lee, H. Qiu, G. Yu, J. Lin, and Rexnord Technical Services (2007). IMS, University of Cincinnati. "
          + "\"Bearing Data Set\", NASA Prognostics Data Repository, NASA Ames Research Center, Moffett Field, CA";
  public static final String IMS_PUBLISHER = "NASA Ames Research Center Prognostics Data Repository";

  public static final String LOG_ID = "loghub-hdfs";
  public static final String LOG_NAME = "Loghub HDFS_2k";
  public static final String LOG_PAGE = "https://github.com/logpai/loghub/tree/master/HDFS";
  public static final String LOG_DOWNLOAD =
      "https://raw.githubusercontent.com/logpai/loghub/master/HDFS/HDFS_2k.log";
  public static final String LOG_LICENSE =
      "Loghub research and academic use. Refer to https://github.com/logpai/loghub and cite Zhu et al., ISSRE 2023.";
  public static final String LOG_LICENSE_URL = "https://github.com/logpai/loghub/blob/master/LICENSE";
  public static final String LOG_CITATION =
      "Wei Xu, Ling Huang, Armando Fox, David Patterson, and Michael Jordan. Detecting Large-Scale System "
          + "Problems by Mining Console Logs. SOSP 2009. Jieming Zhu, Shilin He, Pinjia He, Jinyang Liu, and "
          + "Michael R. Lyu. Loghub: A Large Collection of System Log Datasets for AI-driven Log Analytics. ISSRE 2023.";
  public static final String LOG_PUBLISHER = "Loghub";

  public static final String MVTEC_ID = "mvtec-ad";
  public static final String MVTEC_NAME = "MVTec AD";
  public static final String MVTEC_PAGE = "https://www.mvtec.com/research-teaching/datasets/mvtec-ad";
  public static final String MVTEC_LICENSE = "CC BY-NC-SA 4.0";
  public static final String MVTEC_LICENSE_URL = "https://creativecommons.org/licenses/by-nc-sa/4.0/";
  public static final String MVTEC_CITATION =
      "Paul Bergmann, Kilian Batzner, Michael Fauser, David Sattlegger, and Carsten Steger. "
          + "The MVTec Anomaly Detection Dataset: A Comprehensive Real-World Dataset for Unsupervised Anomaly Detection. "
          + "International Journal of Computer Vision, 2021.";
  public static final String MVTEC_PUBLISHER = "MVTec Software GmbH";

  public static final String INCIDENT_ID = "netflix-public-postmortem";
  public static final String INCIDENT_NAME = "Netflix public postmortems";
  public static final String INCIDENT_LICENSE =
      "Copyright Netflix. Fetched into the local database for this demo. This repository does not redistribute the article text.";
  public static final String INCIDENT_PUBLISHER = "Netflix";

  public static final String CODE_ID = "netflix-public-code";
  public static final String CODE_NAME = "Netflix public repository READMEs";
  public static final String CODE_PUBLISHER = "Netflix";

  public static final String VIBRATION_MODEL = "embedding-zoo/ims-vibration-features-v1";
  public static final String VIBRATION_ARTIFACT =
      "Population moments and a Hann-windowed power spectrum of the mean-centered channel, then L2 normalization. "
          + "Dimensions: log1p(RMS), standardized kurtosis/10, skewness, crest/10, log1p(peak-to-peak), log1p(std), "
          + "spectral centroid/Nyquist, and five band-energy fractions (0-1, 1-2, 2-4, 4-8, 8-10 kHz at the documented 20 kHz sample rate).";
  public static final String IMAGE_MODEL = "embedding-zoo/rgb-histogram-4x4x4-v1";
  public static final String IMAGE_ARTIFACT =
      "64-bin RGB histogram (4 bins per channel), L2-normalized. Appearance similarity only.";

  public static final String ANALOGY_DISCLAIMER =
      "NASA IMS bearings, MVTec AD, Loghub HDFS, Netflix public postmortems, and Netflix public READMEs are separate "
          + "collections. A verified relationship means the records share provenance stored at ingest: the same snapshot, "
          + "the same source document, or the same dataset. An analogous pattern is cosine similarity in one embedding "
          + "space between records from different datasets. Shared publisher names are catalog attributions. None of "
          + "these scores is evidence that one record caused another.";

  private Datasets() {}
}
