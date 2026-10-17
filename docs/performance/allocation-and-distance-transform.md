# Measured performance follow-up — 2026-10-17

The local candidate `c617b88` delivers a modest overall CPU improvement while preserving exact tested output.
In the matched Android emulator workload, whole-process CPU per geometry build fell **4.3%** versus original `c0ebad4`, geometry CPU fell **7.2%**, and allocated bytes fell **20.1%**.
GC remains a regression: median collections increased **23 → 35**, and ART GC time increased **203 → 266 ms**.
The final contour/EDT changes improve compute and allocation beyond packed checkpoint `7b9274e`, but do not establish faster final presentation or universally faster frames.

## Selected changes

Packed geometry writes the same F16 bytes once instead of retaining Float arrays and converting them on upload.
Primitive contour storage removes temporary point objects and shares immutable segment endpoints while preserving arithmetic order and deterministic Tumble.
The horizontal Euclidean distance transform now uses two scans specialized for its binary mask; the general transform still runs down columns.
It preserves the unsigned-byte threshold (`<=127`), finite `1e12f` cap, Float quantization, and cancellation checkpoints, with smaller scratch arrays on wide fields.
No shader, light/color/frost, quality level, public API, or accessibility behavior changes are introduced.

There is no new production cache. The packed checkpoint's shared geometry LRU remains capped at 32 entries and an estimated **4 MiB CPU budget**, down from the original 8 MiB.
CPU texture payload remains 16 rather than 32 bytes per field pixel; the seven-component kernel fixture uses 1,290,112 rather than 2,580,224 bytes.
The existing bounded worker cloud field and padded-upload scratch bounds remain unchanged. Current surfaces, in-flight work, native copies, and GPU storage are outside the LRU budget.
The reported payload counts are neither total process RAM nor measured GPU residency. See [the packed checkpoint](packed-geometry.md) for its storage accounting.

## Matched Android emulator results

The final comparison used the same API 36 arm64 emulator on an Apple M3 Max, seven visible Components surfaces, a 1600 × 2400 viewport, and 320 dpi.
Optimized profileable APKs received speed AOT. Fresh-process blocks ran original → packed → final → final → packed → original.
Each block had one warmup and two measured runs; each measured run contained two 600 ms Tumble drags separated by 250 ms.
Four runs per variant, with 483–490 completed builds per run, are retained. No host analysis or builds ran during capture.
This reversed order reduces one-way drift; it is not randomization or a significance test.

| Median measure | Original `c0ebad4` | Packed `7b9274e` | Final `c617b88` |
|---|---:|---:|---:|
| ART allocated bytes / completed build | 936,170 | 763,809 | 748,239 |
| Geometry scheduled CPU ms / build | 1.1684 | 1.1384 | 1.0839 |
| Whole-process CPU ms / build | 3.1862 | 3.1128 | 3.0493 |
| App scheduled CPU ms / build | 3.3418 | 3.2618 | 3.2088 |
| Texture upload scheduled CPU ms / capture | 136.46 | 28.61 | 29.55 |
| Instrumented UI p50 / p95, ms | 4.344 / 5.525 | 2.684 / 3.518 | 2.876 / 3.670 |
| ART collections / GC time, ms | 23 / 203 | 35 / 279 | 35 / 266 |
| HeapDaemon scheduled CPU ms | 199.15 | 279.73 | 270.03 |
| Compaction pause sum, ms | 5.95 | 8.94 | 8.42 |
| Last MOVE → final presentation, ms | 43.449 | 43.147 | 43.190 |

Against packed alone, final geometry CPU/build fell 4.8%, whole-process CPU/build 2.0%, and bytes/build 2.0%.
Final UI p95 was 4.3% higher than packed, while remaining 33.6% below original. The result is a compute/allocation improvement, not an across-the-board frame-time improvement.
Whole-process allocation/CPU divided by observed builds includes framework and background work; it is not an isolated kernel measurement.
Runtime endpoint intervals are about 2.75 s; scheduled CPU uses first DOWN through final UP + 1 s, about 2.49 s. UI percentiles are medians of per-run percentiles.

All 24 final gesture cohorts applied and presented all seven requested surfaces; main traces reported no data loss or incomplete UI/geometry slices.
Final MOVE-to-presentation ranges were 42.09–57.88 / 42.00–43.95 / 42.10–58.12 ms for original / packed / final.
There is no meaningful final-settling improvement here. Sparse deadline-miss counts do not establish a jank-rate or FPS improvement.
[Per-run data](followup/android/analysis/final-followup-per-run.csv), [revision-aware gestures](followup/android/analysis/final-followup-gestures.csv), [comparison and methodology](followup/android/analysis/final-followup-summary.json).

## Allocation counts, memory, and the remaining GC cost

The reduction in allocated bytes is reproducible across the separate controlled experiments. More frequent collection also persists.
Smaller managed-heap snapshots are consistent with a changed collection cadence, but the traces do not prove why ART selected that cadence.
Zero reported blocking GC does not mean zero pauses or overhead; background GC and compaction still consume time and CPU.
Final GC time is lower than packed in this comparison, but remains 31% above original, with essentially unchanged collection count versus packed.

Separate counting-enabled runs observed 1,843.63 / 1,857.46 / 1,204.94 whole-process objects per completed build for original / packed / final.
The final observation is about 35% below packed. Each variant has one such diagnostic run; counting was disabled for all timing runs.
These deprecated approximate counters include framework work, do not identify object types, and cannot establish retained objects; ART object counts were unavailable.
Legacy GC counters returned zero despite ART and traces observing collections, so legacy zero is not evidence of no GC.
[Counting evidence and observed build denominators](followup/android/analysis/final-followup-count-summary.json).

Allocated bytes measure cumulative throughput; object counts measure allocation frequency. Neither measures simultaneous retention.
Heap used/total, native allocator gauges, and PSS depend on collection timing and process history and cannot establish leaks or retained-size changes.
Post-workload PSS medians were 77,274 / 65,579.5 / 66,427 KiB for original / packed / final; these are contextual snapshots, not a total-memory guarantee.
Separate idle observations allocated 20,528 bytes with zero GC and about 1 ms Process CPU over roughly 3.25 s; observer cost is included, not subtracted.
[Android runtime-stat semantics](https://developer.android.com/reference/android/os/Debug#getRuntimeStat(java.lang.String)), [ART GC diagnostics](https://source.android.com/docs/core/runtime/gc-debug).

## JVM kernels and first paint

The matched kernel comparison uses packed → final, two process rounds, 60 samples per case per round, 30 warmups and two initialization passes.
All 960 paired samples match both exact F16 and contour/uniform checksums: **1,920 exact checks**, with identical texture/cloud/native payload fields.
Current-thread JVM allocated bytes fall by **152,456 per seven-component build**, without a new retained cache.

| Seven-component case | Combined wall median / p95, packed → final | CPU median, packed → final | Allocated bytes, packed → final |
|---|---:|---:|---:|
| Fresh cloud field | 5.731 / 6.705 → 5.362 / 6.350 ms | 5.709 → 5.340 ms | 5,812,976 → 5,660,520 |
| Reused worker cloud field | 5.154 / 5.957 → 4.876 / 5.590 ms | 5.150 → 4.837 ms | 5,479,384 → 5,326,928 |

Button/toolbar cases improve; the reused disc is approximately unchanged and regresses slightly in the second round.
ThreadMXBean counts heap bytes on the current thread, excluding native allocation and other threads. Combined measurements include native texture construction, not drawing or GPU execution.
[Kernel findings](followup/jvm-comparison/findings.md), [all case summaries](followup/jvm-comparison/summary.csv).

The separate desktop first-paint proxy compares original → final against 21 frozen reference images, with cold/revisit runs for seven fixtures and three seeds.
Both variants reach exact reference pixels in **42/42 observations**. Cold final-exact medians are 48.143 → 46.765 ms; revisit medians are 42.891 → 41.369 ms.
Immediate renders match in 25/42 → 30/42 observations, but cold immediate matches remain 12/21 for both. Individual fixtures are mixed.
This 520 × 1000 software composition/draw/readback proxy uses 4 ms polling and one process per variant; it is neither display presentation nor evidence of eliminated pop-in.
[First-paint data and limitations](followup/first-paint-analysis/first-paint-comparison.json).

## Fresh-process Android startup

Three original and three final launches ran separately, with speed AOT and force-stop between captures; existing OS/driver caches were not cleared.
First-material-frame start → display medians were **205.106 → 194.826 ms**, with overlapping ranges of 191.715–206.644 and 192.362–204.575 ms.
That interval excludes earlier launch work. `am start` completion medians were 283 → 272 ms, but do not prove final material pixels.
App scheduled CPU was **248.316 → 249.730 ms**. Each run compiled 29 shaders, with median total compile wall time **113.682 → 113.714 ms**.
Geometry/upload CPU was lower, but these samples establish neither lower total startup CPU nor reduced shader compilation cost.
All six initial seven-surface cohorts applied and presented together in their first material frame; every first material frame missed its app deadline.
Two candidate traces retained stale process names; verified process IDs resolved their telemetry. There was no reported data loss or incomplete UI/geometry slice.
This is a small fresh-process sample, not a cold-cache or nearly-instant claim. [Cold summary](followup/android/analysis/final-cold-summary.json), [six observations](followup/android/analysis/final-cold-per-run.csv).

## Rejected alternatives

| Experiment | Measured tradeoff | Decision |
|---|---|---|
| Row-ring smoothing | Another 6.03% lower Android bytes/build; no GC or whole-process CPU gain, slightly higher geometry CPU | Excluded: complexity without demonstrated latency/GC benefit |
| Bounded contour basis cache | 6,400 retained array bytes per usual worker; about 1% less geometry CPU versus contour alone, no object-count, GC, or settling benefit | Excluded: small, variable gain did not justify extra retained state |

These were separate experiments and must not be combined into a synthetic final benchmark. The final candidate contains neither change.
Raw historical records remain locally under `evidence/gc-followup/`, `evidence/contour-followup/`, and `evidence/basis-followup/` in the task workspace.
In the old GC experiment only, the label `performance` means rejected row-ring; in `final-followup` it means selected `c617b88`.

## Correctness and remaining limits

Validation passed **73 JVM tests, eight desktop verification groups, five Android uploader tests, and iOS compilation**.
Contour tests compare 780 fixtures to an independent frozen reference using exact Double bits; EDT tests retain the old general transform and cover exhaustive small masks, threshold bytes, random rows, distances exceeding the finite sentinel, and cancellation.
Adversarial complete-field comparisons cover all six Float fields and alpha; independent contour tests cover the contour generation shared by the field oracle.
The Android reset-state screenshot comparison has **zero changed pixels** in the documented `(0,144)–(1600,2304)` crop. One settled screenshot does not prove every animated state.
[JVM log](followup/jvm-tests.log), [desktop log](followup/desktop-verify.log), [Android tests](followup/android-uploader-tests.json), [visual comparison](followup/android/visual-comparison.json).

The test-only snapshot fix synchronizes headless Compose delivery through AWT before one requested frame; it adds no forced redraw, extra frame, sleep, or retry and leaves assertions intact.
The earlier release genuinely executed six iOS tests with uncached execution on its reported Xcode 27.0 setup.
The later environment has Xcode 27.1 and mismatched CoreSimulator components; final iOS compilation passes, but simulator execution remains blocked before tests.
No license, first-launch setup, security change, or runtime installation was performed. The evidence does not establish when or how Xcode changed.
[Compile log](followup/ios-compile.log), [iOS prerequisite reconciliation](followup/ios-reconciliation.json).

Android measurements are from one emulator; physical Android, iOS runtime, battery, thermal, and physical GPU performance remain unmeasured.
CPU upload/RenderThread/shader-driver slices are not GPU execution costs; no usable GPU execution counters were available.
Seven visible components provide multi-component evidence; the earlier scrolling attempt produced no useful draw workload and was excluded.
The evidence supports accepting the scoped compute/allocation improvements locally while retaining the GC regression and platform limits. It does not support nearly-instant or universal speed claims.
Reproduction scripts, manifests, exact source/APK identities, and raw-data locations are indexed in [the evidence guide](followup/README.md).
The branch is local `codex/performance-followup`; no push, release publication, or remote CI is part of this work.
