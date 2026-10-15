# Datasets

Each record stores the source URL, license text, citation, model id, and a content hash. This
repository does not commit the downloaded archives, log lines, article text, or images. Those stay
under `data/`, which is gitignored.

## NASA IMS bearings

| | |
|---|---|
| Page | https://data.nasa.gov/dataset/ims-bearings |
| File used | https://phm-datasets.s3.amazonaws.com/NASA/4.+Bearings.zip |
| Portal license label | `other-license-specified` |
| Portal license link | https://www.usa.gov/government-works |
| Publisher on the portal | Prognostics Center of Excellence, hosting data provided by the Center for Intelligent Maintenance Systems (IMS), University of Cincinnati |

The zip contains `IMS.7z`. This demo extracts `1st_test.rar` only. junrar 7.5.5 reports
`CrcErrorException` before the final snapshots of that solid RAR, so Linux x64 runs use 7-Zip 26.02
(`https://www.7-zip.org/a/7z2602-linux-x64.tar.xz`, GNU LGPL with the 7-Zip unRAR restriction). The
binary stays under `data/tools` and is not committed. The readme inside that archive,
*Readme Document for IMS Bearing Data*, states:

- Four bearings on one shaft, 2000 RPM, 6000 lbs radial load, force lubrication.
- Each file is a 1-second ASCII snapshot of 20,480 points at 20 kHz. The filename is the collection time.
- Set 1: 22 October 2003 12:06:24 through 25 November 2003 23:39:56, 2,156 files, 8 channels. Bearing 1 is channels 1–2, bearing 2 is channels 3–4, bearing 3 is channels 5–6, bearing 4 is channels 7–8.
- End of set 1: inner race defect in bearing 3 and roller element defect in bearing 4. That sentence describes the end of the test. It is stored as metadata with `label_scope` and is not written into the embedded text.

Shaft speed and load are copied onto records only when `pdftotext` can read those phrases from the packaged readme.

Citation stored on each record:

> J. Lee, H. Qiu, G. Yu, J. Lin, and Rexnord Technical Services (2007). IMS, University of Cincinnati. "Bearing Data Set", NASA Prognostics Data Repository, NASA Ames Research Center, Moffett Field, CA.

The readme also cites Hai Qiu, Jay Lee, and Jing Lin, “Wavelet Filter-based Weak Signature Detection Method and its Application on Roller Bearing Prognostics,” Journal of Sound and Vibration 289 (2006) 1066–1090.

`data.nasa.gov/docs/legacy/IMS.zip` redirects to a signed URL that rejects a plain HEAD request. The S3 URL above is the file this demo downloads.

## Loghub HDFS_2k

| | |
|---|---|
| Page | https://github.com/logpai/loghub/tree/master/HDFS |
| File | https://raw.githubusercontent.com/logpai/loghub/master/HDFS/HDFS_2k.log |
| License | Research or academic use. Any use or distribution must refer to https://github.com/logpai/loghub and cite Zhu et al., ISSRE 2023. The license file in the repository is not the Apache license. |

The 2,000-line file is the small sample published in that folder. Lines include the published addresses and block ids; this demo does not redact them. The sample has no per-line anomaly label, and none is invented here. Timestamps in the file use the `081109` style of the HDFS traces described by Xu, Huang, Fox, Patterson, and Jordan, SOSP 2009.

## Netflix public postmortems

Catalog titles and canonical URLs are in `src/main/resources/incidents/catalog.json`:

- A Closer Look at the Christmas Eve Outage (2012-12-24)
- Post-mortem of October 22, 2012 AWS degradation
- Lessons Netflix Learned from the AWS Outage (2011-04-21)

Article text is fetched at ingest into `data/cache` and is not committed. The pages are copyright Netflix. A short or blocked response is skipped. If the publisher page is blocked, ingest tries one Internet Archive snapshot with HTTP 200. A title is never stored as if it were the article body.

## Netflix public READMEs

`Netflix/chaosmonkey`, `Netflix/zuul`, `Netflix/Hystrix`, `Netflix/eureka`, and `Netflix/conductor`. A repository is skipped when the GitHub API does not report an SPDX license. The SPDX id GitHub returns is stored on the record. These records use dataset id `netflix-public-code`. The postmortems use `netflix-public-postmortem`. Similarity between them is an analogy even though both publishers are Netflix.

## MVTec AD (optional)

| | |
|---|---|
| Page | https://www.mvtec.com/research-teaching/datasets/mvtec-ad |
| License | CC BY-NC-SA 4.0. Non-commercial. https://creativecommons.org/licenses/by-nc-sa/4.0/ |
| Citation | Paul Bergmann, Kilian Batzner, Michael Fauser, David Sattlegger, and Carsten Steger. The MVTec Anomaly Detection Dataset: A Comprehensive Real-World Dataset for Unsupervised Anomaly Detection. International Journal of Computer Vision, 2021. |

The archive is large and is not downloaded automatically. Place files yourself and run `accept-mvtec`. Ground-truth mask folders are skipped. The folder name is stored as a directory label, not as a prediction.

## Models

| Space | Id | Notes |
|---|---|---|
| Text | `sentence-transformers/all-MiniLM-L6-v2` | Apache-2.0. ONNX file `Xenova/all-MiniLM-L6-v2/onnx/model.onnx`. |
| Vibration | `embedding-zoo/ims-vibration-features-v1` | Classical features of one channel. Same model required before two vibration vectors are compared. |
| Image | `embedding-zoo/rgb-histogram-4x4x4-v1` | 64-bin RGB histogram. Appearance only. |

`EMBEDDING_ZOO_TEXT_PROVIDER=openai` switches text embeddings to `text-embedding-3-small`. Re-ingest after changing the text model. Record ids include the model id, so the two text models do not overwrite each other.

## Not in this demo

FSD50K audio, Hugging Face video, and further NASA geospatial sets are separate collections. This module does not ingest them and does not infer a relationship from the fact that a URL was listed in the project brief.
