package com.example.nearmiss;

/**
 * Licenses, citations, and source URLs for the corpora this demo ingests.
 *
 * <p>The strings here are bibliographic. They are copied onto each stored record. They are not a
 * substitute for the dataset owners' terms.
 */
public final class Datasets {

  public static final String TEXT_MODEL = "sentence-transformers/all-MiniLM-L6-v2";
  public static final String TEXT_ARTIFACT = "Xenova/all-MiniLM-L6-v2/onnx/model.onnx";
  public static final String TEXT_MODEL_INT8 = TEXT_MODEL + ":int8";
  public static final String TEXT_ARTIFACT_INT8 = "Xenova/all-MiniLM-L6-v2/onnx/model_quantized.onnx";
  public static final String TEXT_LICENSE = "Apache-2.0";
  public static final String TEXT_LICENSE_URL = "https://www.apache.org/licenses/LICENSE-2.0.txt";
  public static final String ONNX_INT8_URL =
      "https://huggingface.co/Xenova/all-MiniLM-L6-v2/resolve/main/onnx/model_quantized.onnx";
  public static final String VOCAB_URL =
      "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/vocab.txt";
  public static final String ONNX_URL =
      "https://huggingface.co/Xenova/all-MiniLM-L6-v2/resolve/main/onnx/model.onnx";

  /** The kinds a record can have, one per source. {@code kind} is also the filter the tools expose. */
  public static final String KIND_DEVICE = "device";
  public static final String KIND_VEHICLE = "vehicle";
  public static final String KIND_AVIATION = "aviation";

  public static final String MAUDE_ID = "fda-maude";
  public static final String MAUDE_NAME = "FDA MAUDE medical device adverse event reports";
  public static final String MAUDE_PAGE = "https://open.fda.gov/apis/device/event/";
  public static final String MAUDE_API = "https://api.fda.gov/device/event.json";
  public static final String MAUDE_LICENSE = "CC0 1.0 Universal, as stated for openFDA content";
  public static final String MAUDE_LICENSE_URL = "https://open.fda.gov/license/";
  public static final String MAUDE_CITATION =
      "U.S. Food and Drug Administration. Manufacturer and User Facility Device Experience (MAUDE), via openFDA. "
          + "Reports are submitted by manufacturers, user facilities, and the public and are not verified by the FDA.";
  public static final String MAUDE_PUBLISHER = "U.S. Food and Drug Administration";

  public static final String NHTSA_ID = "nhtsa-complaints";
  public static final String NHTSA_NAME = "NHTSA vehicle owner complaints";
  public static final String NHTSA_PAGE = "https://www.nhtsa.gov/nhtsa-datasets-and-apis";
  public static final String NHTSA_API = "https://api.nhtsa.gov/complaints/complaintsByVehicle";
  public static final String NHTSA_LICENSE = "U.S. Government work. Published by NHTSA as open data.";
  public static final String NHTSA_LICENSE_URL = "https://www.usa.gov/government-works";
  public static final String NHTSA_CITATION =
      "National Highway Traffic Safety Administration. Vehicle owner complaints (Office of Defects Investigation). "
          + "Complaints are statements by vehicle owners and are not confirmed defects.";
  public static final String NHTSA_PUBLISHER = "National Highway Traffic Safety Administration";

  public static final String ASRS_ID = "nasa-asrs";
  public static final String ASRS_NAME = "NASA ASRS report sets";
  public static final String ASRS_PAGE = "https://asrs.arc.nasa.gov/search/reportsets.html";
  public static final String ASRS_REPORT_SET_URL = "https://asrs.arc.nasa.gov/docs/rpsts/%s.pdf";
  public static final String ASRS_LICENSE =
      "Published by the NASA Aviation Safety Reporting System for public use. No separate license text is given. "
          + "NASA-authored material is generally not subject to copyright in the United States.";
  public static final String ASRS_LICENSE_URL = "https://asrs.arc.nasa.gov/overview/caveats.html";
  public static final String ASRS_CITATION =
      "NASA Aviation Safety Reporting System (ASRS) Database Report Set. Reports are voluntary, de-identified "
          + "submissions that NASA, the FAA and the NTSB have not corroborated.";
  public static final String ASRS_PUBLISHER = "NASA Aviation Safety Reporting System";

  public static final String DISCLAIMER =
      "These are public safety reports, not findings. A report says what someone submitted. It does not say "
          + "that a device, vehicle or aircraft was defective or that it caused the outcome described.";

  public static final String ANALOGY_DISCLAIMER =
      "FDA MAUDE, NHTSA complaints and NASA ASRS reports are separate collections, submitted by different people "
          + "to different agencies. A verified relationship means the records share provenance stored at ingest: the "
          + "same report, the same product code, the same vehicle or aircraft, or the same report set. An analogous pattern is cosine similarity in "
          + "one embedding space between records from different datasets. Shared publisher names are catalog "
          + "attributions. None of these scores is evidence that one record caused another, or that a device, "
          + "vehicle or aircraft is defective.";

  private Datasets() {}
}
