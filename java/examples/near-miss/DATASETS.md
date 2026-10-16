# Datasets

Each record stores the source URL, license text, citation, model id, and a content hash. This
repository does not commit report text. Responses from the APIs are cached under `data/`, which is
gitignored.

All three sources are collections of reports that people submitted. A report says what the submitter
wrote. It is not a finding, and no agency has confirmed that a device, a vehicle or an aircraft was
defective or that it caused the outcome described.

## FDA MAUDE (medical devices)

| | |
|---|---|
| Page | https://open.fda.gov/apis/device/event/ |
| API | https://api.fda.gov/device/event.json |
| License | CC0 1.0 Universal, as stated for openFDA content: https://open.fda.gov/license/ |
| Dataset id | `fda-maude` (kind `device`) |

The Manufacturer and User Facility Device Experience database holds reports from manufacturers,
user facilities and the public. For each of eight device names (`infusion pump`, `insulin pump`,
`pacemaker`, `defibrillator`, `ventilator`, `hip`, `catheter`, `glucose`) ingest asks for the most
recent reports received since 2020 with an event type of `Death` or `Injury` and a text narrative.

Kept from each report: the report number, event type, date received, the device's generic name,
brand name, manufacturer and product code, and the opening of the "Description of Event or Problem"
text. Not read: any patient field.

The FDA's own caveat applies: reports are not verified, may be incomplete or inaccurate, and the
number of reports for a device says nothing about how many events occurred or how safe it is.

## NHTSA complaints (vehicles)

| | |
|---|---|
| Page | https://www.nhtsa.gov/nhtsa-datasets-and-apis |
| API | https://api.nhtsa.gov/complaints/complaintsByVehicle |
| License | U.S. Government work, published as open data: https://www.usa.gov/government-works |
| Dataset id | `nhtsa-complaints` (kind `vehicle`) |

Owner complaints to the Office of Defects Investigation, for six vehicles: Tesla Model 3 (2020),
Toyota Prius (2012), Honda Accord (2018), Chevrolet Bolt EV (2019), Jeep Grand Cherokee (2016) and
Ford F-150 (2018). Complaints that report a crash, a fire, an injury or a death come first.

Kept from each complaint: the ODI number, the vehicle, the components, whether it reports a crash or
fire, the injury and death counts, the filing date, and the summary text. The VIN fragment the API
returns is not stored.

## NASA ASRS (aviation)

| | |
|---|---|
| Page | https://asrs.arc.nasa.gov/search/reportsets.html |
| Files | `https://asrs.arc.nasa.gov/docs/rpsts/<set>.pdf` for `icing`, `fuel`, `cabin_fumes`, `upsets`, `mechanic` and `gps` |
| License | No license text is given. NASA-authored material is generally not subject to copyright in the United States. Check the terms before redistributing it. |
| Dataset id | `nasa-asrs` (kind `aviation`) |

The Aviation Safety Reporting System collects voluntary reports from pilots, controllers and
mechanics. NASA publishes curated "report sets" of about 50 reports on a topic as PDFs. Each PDF lists
the reports twice, as synopses and then in full. Ingest keeps the first 25 reports with a narrative
from each set: the ACN, date, aircraft, flight phase, the synopsis and the first narrative. The PDF
text is extracted with Apache PDFBox.

NASA's own memo in each PDF applies: reports are voluntary, not corroborated by NASA, the FAA or
the NTSB, and the number of reports on a topic says nothing about how common the problem is.

## Models

| Id | Notes |
|---|---|
| `sentence-transformers/all-MiniLM-L6-v2` | Apache-2.0. ONNX file `Xenova/all-MiniLM-L6-v2/onnx/model.onnx`. |
| `sentence-transformers/all-MiniLM-L6-v2:int8` | The same model, quantized (`.../onnx/model_quantized.onnx`, `NEAR_MISS_TEXT_PROVIDER=minilm-int8`). A separate model id: never compare its vectors with the float32 ones. |

`NEAR_MISS_TEXT_PROVIDER=openai` switches to `text-embedding-3-small`. Re-ingest after changing
the model. Record ids include the model id, so two models do not overwrite each other.

## Not in this demo

Nothing else is ingested, and no relationship is inferred from the fact that the collections all
cover safety.
