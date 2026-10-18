# JVM comparison: checkpoint → performance

All 960 paired samples match both exact F16 and contour/uniform checksums (1920 checks). Required-equal payload fields: texturePayloadBytes, cloudCachePayloadBytes, nativeRasterPayloadBytes. 2 rounds, 60 samples/case/variant/round, 30 warmups plus 2 initialization passes.

| Case | Round | Combined wall median/p95 ms before→after | Combined CPU median ms before→after | Allocated bytes median before→after |
|---|---|---:|---:|---:|
| freshClouds.button | 1 | 1.868/2.383→1.804/2.133 | 1.875→1.802 | 1670464→1645696 |
| freshClouds.button | 2 | 1.919/2.251→1.693/2.140 | 1.911→1.694 | 1670600→1645696 |
| freshClouds.button | pooled | 1.897/2.287→1.743/2.140 | 1.906→1.744 | 1670464→1645696 |
| reusedClouds.button | 1 | 1.531/1.876→1.434/1.704 | 1.529→1.430 | 1571488→1546720 |
| reusedClouds.button | 2 | 1.500/1.880→1.453/1.708 | 1.495→1.455 | 1571488→1546720 |
| reusedClouds.button | pooled | 1.523/1.880→1.437/1.708 | 1.520→1.438 | 1571488→1546720 |
| freshClouds.toolbar | 1 | 2.240/2.857→2.159/2.559 | 2.236→2.115 | 2276216→2245624 |
| freshClouds.toolbar | 2 | 2.263/2.642→2.053/2.426 | 2.262→2.051 | 2276216→2245624 |
| freshClouds.toolbar | pooled | 2.246/2.763→2.079/2.499 | 2.245→2.078 | 2276216→2245624 |
| reusedClouds.toolbar | 1 | 2.092/2.420→1.975/2.255 | 2.038→1.978 | 2140208→2109616 |
| reusedClouds.toolbar | 2 | 1.977/2.356→1.822/2.144 | 1.977→1.824 | 2140208→2109616 |
| reusedClouds.toolbar | pooled | 2.000/2.420→1.887/2.201 | 1.997→1.881 | 2140208→2109616 |
| freshClouds.disc | 1 | 0.327/0.445→0.299/0.400 | 0.328→0.301 | 330400→312952 |
| freshClouds.disc | 2 | 0.325/0.420→0.307/0.413 | 0.328→0.309 | 330400→312952 |
| freshClouds.disc | pooled | 0.325/0.431→0.305/0.408 | 0.328→0.307 | 330400→312952 |
| reusedClouds.disc | 1 | 0.307/0.379→0.301/0.360 | 0.309→0.303 | 313208→295760 |
| reusedClouds.disc | 2 | 0.300/0.374→0.306/0.347 | 0.301→0.307 | 313208→295760 |
| reusedClouds.disc | pooled | 0.305/0.379→0.304/0.360 | 0.307→0.305 | 313208→295760 |
| freshClouds.scene | 1 | 5.922/6.705→5.402/6.664 | 5.836→5.399 | 5812976→5660520 |
| freshClouds.scene | 2 | 5.681/6.680→5.333/6.100 | 5.660→5.324 | 5812976→5660520 |
| freshClouds.scene | pooled | 5.731/6.705→5.362/6.350 | 5.709→5.340 | 5812976→5660520 |
| reusedClouds.scene | 1 | 5.175/5.957→4.905/5.617 | 5.162→4.849 | 5479384→5326928 |
| reusedClouds.scene | 2 | 5.128/5.916→4.838/5.476 | 5.122→4.824 | 5479384→5326928 |
| reusedClouds.scene | pooled | 5.154/5.957→4.876/5.590 | 5.150→4.837 | 5479384→5326928 |

Build-cache policy before: (not reported by legacy harness)

Build-cache policy after: (not reported by legacy harness)

| Case | Reuse contour basis before→after | Retained contour basis bytes median before→after |
|---|---|---:|
| freshClouds.button | False→False | 0→0 |
| reusedClouds.button | False→False | 0→0 |
| freshClouds.toolbar | False→False | 0→0 |
| reusedClouds.toolbar | False→False | 0→0 |
| freshClouds.disc | False→False | 0→0 |
| reusedClouds.disc | False→False | 0→0 |
| freshClouds.scene | False→False | 0→0 |
| reusedClouds.scene | False→False | 0→0 |

ThreadMXBean reports allocated heap BYTES on the current thread, not object counts. No model or expected byte-saving assumption is used. Native allocation and other threads are excluded. Combined stage sums are computed per observation before statistics.

Exact texture/cloud primitive payload fields and native raster estimate do not count contour object storage. Optional retainedContourBasisBytes is reported separately; absent legacy values are treated as zero and reporting presence is recorded. geometryCacheEstimateBytes is an implementation accounting estimate, not a heap measurement; contour changes can alter it. Equal texture/cloud/native payloads do not establish unchanged total retention.

buildCachePolicy and per-case reuseContourBasis may intentionally differ between variants and are reported explicitly, while within-variant policy drift between rounds is rejected. Legacy absent reuseContourBasis means false. Timed repeated cases can therefore compare different internal cache policies without changing fixture, Tumble, oracle or sample order.

CPU geometry and native raster texture creation; not GPU execution or physical Android performance. Fixed-order rounds retained separately; no statistical significance or production recommendation inferred automatically.

Every paired allocation delta is retained in `paired-allocation-deltas.csv`; CPU/stage medians and p95 ratios are in `summary.csv`. All fields, input hashes and per-case observed delta distributions are in `comparison.json`. No allocation-saving model or recommendation is embedded in this comparator.
