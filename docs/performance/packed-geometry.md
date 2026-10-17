# Packed live geometry — October 17, 2026

This local follow-up to `c0ebad4857d475a360e62e21c13606e01ff8c6fa` reduces retained CPU texture storage and drawing-thread texture work without changing the public API, shader equations, sample density, deterministic Tumble, color/frost/light behavior or accessibility. It does **not** remove cold shader compilation, reduce the native F16 texture size, or establish a physical-device/GPU performance improvement.

The motivation was [Romain Guy's compute and memory observation](https://bsky.app/profile/romainguy.dev/post/3my3jdmao3k2t). The retrieved public thread contained no more specific technical diagnosis. The measurements below establish the scope independently of that comment. Previous [CPU work](README.md) and [Android profiling](android-emulator-profile.md) already covered blur, caches, uniform invalidation and moving Tumble geometry off the UI thread; those changes are retained.

## Change and memory bounds

The old live geometry retained two RGBA `FloatArray`s and converted them to F16 whenever a surface uploaded them. Both graphics backends already consumed F16. Geometry now writes those **same F16 bytes** directly, once, on the geometry-building thread. Android tightly packed uploads wrap the immutable byte array; padded rows alone use bounded scratch. Skia uses the packed bytes directly. Each native image still owns its independent copy, preserving recorded-draw lifetimes.

- CPU texture payload falls from **32 to 16 bytes per field pixel**. The seven-component kernel fixture falls from **2,580,224 to 1,290,112 bytes**.
- The shared LRU remains capped at 32 entries, with its estimated CPU budget reduced from **8 to 4 MiB**. The accounting includes packed arrays, gradients and conservative contour overhead. Current surface results, transient builds and native/GPU storage are separate from this bound.
- Synchronous first paint/resize no longer retains a redundant cloud field. The worker retains its existing single bounded cloud field, at most 262,144 floats per surface. No scratch pool or new unbounded cache was added.
- Normal Android rows need no retained packing scratch. Padded rows retain at most the existing **2 MiB per shader** bound. Native raster payload is unchanged: **1,290,112 bytes** for the seven-component kernel fixture, excluding overhead/backend copies. This is not measured GPU residency or total app RAM.

No caller has to prepare materials, manage cache lifetimes or select a lower quality level. Public modifier/component/style/theme signatures and call sites are unchanged.

## Android emulator measurements

Apple M3 Max, macOS 27.0.1, API 36 arm64 emulator, `Minimap_Plugin_Demo_20261009`, 1600 × 2400 px at 320 dpi, optimized non-debuggable shell-profileable demo APKs, speed AOT compilation. This is the emulator's host graphics translation backend, **not a physical Android device**. No physical device was connected. The emulator's ephemeral instance automatically stopped during JVM/iOS checks; the candidate used a new read-only instance of the same AVD, then the same viewport, compilation and warmups. Fixed baseline-then-candidate ordering and the restart limit causal/general performance claims.

Three captures per Tumble, Frost, warm scene-switch and process-cold workload; one idle capture per variant. No builds, tests or trace analysis ran during timed device captures. Both seven-component Tumble builds apply **490 geometry updates/uploads per run**. All seven final requested revisions reach presentation in all six drags per variant. Values are medians of per-run values, not pooled frame percentiles.

| Metric | Baseline | Packed geometry |
|---|---:|---:|
| Tumble instrumented UI frame median | 4.54 ms | 2.73 ms |
| Tumble instrumented UI frame p95 | 5.32 ms | 3.74 ms |
| Tumble texture-preparation scheduled CPU per run | 140.20 ms | 29.21 ms |
| Tumble app scheduled CPU, approximately 2.5 s window | 1,640.94 ms | 1,587.98 ms |
| Tumble RenderThread scheduled CPU | 467.61 ms | 466.72 ms |
| Tumble background GC cycles per capture | 23 | 35 |
| Tumble GC wall time per capture | 214.94 ms | 279.34 ms |
| Tumble heap counter peak | 35,768 KiB | 20,916 KiB |
| Cold native shader compilation wall time | 111.94 ms | 112.55 ms |
| Cold first material frame start → presentation | 202.73 ms | 203.12 ms |

The measured UI p95 improves about **30%** and texture-preparation CPU about **79%**, but total app CPU falls only about **3%**. More frequent GC offsets much of the upload saving. GC pause totals increase from 5.98 to 8.42 ms, with individual pauses at most 0.44 ms. A lower live heap may affect ART's collection threshold, but these traces do not prove the cause or measure exact Android allocation bytes. This GC countertrend is retained, not excluded.

Final injected MOVE-to-presentation ranges are 42.3–59.0 ms before and 41.0–45.6 ms after (medians 44.18 → 42.52 ms). These are injected-input emulator results, not physical touch latency. Frost UI p95 is approximately unchanged (3.04 → 3.01 ms), with no geometry builds/uploads in either version. Warm scene switches keep reusing cached geometry, and their UI p95 changes from 16.14 → 14.70 ms. Tumble gfxinfo deadline misses are 2/222 → 3/222; these small counts do **not** establish a jank-rate improvement. All switch revisions apply, but only 54/72 per variant match presentation in that same frame; dropped applied frames leave the other 18 later presentation times unresolved by one-time markers. The idle captures have no draws, so there is no continuous render loop in the measured resting screen.

The [26 per-run measurements and trace hashes](packed-geometry/android-per-run.csv), [gesture rows](packed-geometry/android-gestures.csv), and [combined comparison](packed-geometry/android-comparison.json) retain denominators and ranges. All compared traces have no data-loss flags or incomplete numbered UI frames.

Cold launch remains expensive. Both variants present all seven initial material revisions together, but the first frame misses its deadline in every cold capture. `am start -W` TotalTime medians (288 → 274 ms) do not establish improved material presentation; its measured approximately 203 ms interval is unchanged. Native shader compilation dominates geometry preparation at launch and was not optimized here.

Cold total PSS is approximately unchanged (51,442 → 51,813 KiB median). A post-workload idle snapshot falls from 83,384 to 67,341 KiB, but different process history, timing and collection state make a single snapshot unsuitable for a whole-app memory or leak claim. The array byte counts above and measured heap peaks have narrower, explicit meanings. No GPU execution slices/counters were available; `Graphics: 0` in emulator meminfo does not mean zero graphics memory.

The attempted baseline scrolling gestures did not move the fitting Components pane and produced no drawing; they are excluded. The seven visible components supply the multi-component workload. No sustained thermal, older-Android compatibility-path or iOS performance result is claimed.

## JVM allocation and texture-creation measurements

JDK 17.0.18, software Skia, 256 MiB initial/1 GiB maximum heap, JFR profile enabled identically. Run order B1, C1, B2, C2; 24 samples per case/run, eight per-case warmups plus two initialization warmups. Each stage records current-thread CPU and allocated bytes. Texture creation includes replacement/disposal, **not drawing, GPU upload or presentation**. The same harness runs against both revisions; checksum work is outside timing and allocates the same canonical byte payload in both variants.

| Seven-component case | Geometry median B → C | Native texture creation B → C | Combined median B → C | Allocated JVM bytes B → C |
|---|---:|---:|---:|---:|
| Fresh cloud samples | 5.486 → 5.780 ms | 0.702 → 0.069 ms | 6.188 → 5.860 ms | 8,393,816 → 5,813,368 |
| Reused cloud samples | 5.164 → 5.196 ms | 0.692 → 0.050 ms | 5.843 → 5.275 ms | 8,060,224 → 5,479,776 |

These pooled medians contain 48 observations per case/variant. Combined time is computed per sample before taking its median; it need not equal the sum of stage medians. Allocation falls **28.4–33.0%** across all eight cases. Conversion moves into geometry preparation, and several geometry medians rise. The reused-button combined median increases **2.1%** (slower in both rounds). Fresh-scene combined time improves in round 1 and is essentially unchanged in round 2. The evidence supports cheaper upload and allocation; it does not establish universal CPU speedup.

Raw runs: [B1](packed-geometry/baseline-1.json), [C1](packed-geometry/candidate-1.json), [B2](packed-geometry/baseline-2.json), [C2](packed-geometry/candidate-2.json). [Pooled statistics](packed-geometry/kernel-summary.csv), [individual rounds](packed-geometry/kernel-rounds.csv), and [retained payloads](packed-geometry/retained-memory.csv) include the other fixtures and p95/CPU counters. All **384 paired samples** match both the canonical F16-input and exact contour/uniform SHA-256 checksums (**768 checksum comparisons**).

## First paint, visual and behavioral verification

The separate-process desktop harness uses baseline settled references for both variants, seven fixtures × three seeds × cold/revisit. Both variants finish at exact baseline pixels in **42/42 measurements**. Immediate render calls are exact in 32/42 baseline and 28/42 candidate cases; an observation can precede Compose content insertion. Cold median final-exact latency across fixtures is 47.698 → 48.865 ms; revisit is 42.557 → 41.171 ms. Scene results are mixed, including a slower Music cold insertion. These include software drawing/readback and are not device frame times. See [per-scene medians](packed-geometry/first-paint-summary.csv) and [all observations](packed-geometry/first-paint-raw.csv).

The paired Android Components/Reset screenshot crop `(0,144)–(1600,2304)` has **zero changed RGBA pixels**; system bars are excluded. This static comparison is not a comparison of every animation frame. [Pixel comparison result](packed-geometry/visual-comparison.json).

- **64 JVM tests pass**, including exact geometry/reference, body quality, cache bounds, update ordering and new F16 rounding/storage checks.
- **Five Android device tests pass** on the emulator, using the independent platform `Half.toHalf` reference, all finite-half midpoint neighbors, padded/tight rows and prior-bitmap independence.
- **All eight desktop interaction groups pass**, including focus/keyboard/disabled controls, Material You, color, preview state, callbacks and resizing.
- Optimized Android demo assembly/R8/vital lint and iOS simulator test compilation complete. **iOS test execution remains blocked**: the installed `simctl` wrapper invokes unfinished `xcodebuild -runFirstLaunch` setup. Only this task's pending launcher/setup processes were stopped. No iOS test pass is claimed.

The combined check command ends failed because of that iOS launcher prerequisite, despite the completed JVM/Android/desktop checks. [Test record](packed-geometry/test-results.json). Static review found no blocking correctness or API regression.

## Reproduce and inspect

Run the same benchmark harness/task on the base commit and candidate, in separate local checkouts. Build before timed captures, keep other tests/profilers idle, and retain raw runs. No remote CI or publication is needed.

```sh
./gradlew :sea-glass:benchmarkLiveGeometry -PseaGlass.android=false \
  -PbenchmarkSamples=24 -PbenchmarkWarmups=8 \
  -PbenchmarkOutput=/absolute/path/run.json \
  -PbenchmarkProfile=/absolute/path/run.jfr --no-parallel

# Generate references only on the baseline, then measure both against them.
./gradlew :desktopApp:benchmarkFirstPaint -PseaGlass.android=false \
  -PfirstPaintMode=reference -PfirstPaintRounds=3 -PfirstPaintDirectory=/absolute/path/references
./gradlew :desktopApp:benchmarkFirstPaint -PseaGlass.android=false \
  -PfirstPaintMode=measure -PfirstPaintRounds=3 -PfirstPaintDirectory=/absolute/path/references
```

Android uses the [existing capture/analyzer tools](../../tools/android-profile/) and viewport instructions in [the prior profiling report](android-emulator-profile.md#reproduce-the-controlled-workload). This task's additional cold/idle script, analyzers, APKs, JFR files, screenshots and complete Perfetto traces are retained in the sibling local `evidence` directory. APK hashes and environment are in [measurement-context.json](packed-geometry/measurement-context.json). Exploratory JVM data collected concurrently with reference generation is excluded; only the numbered quiet runs above are reported.

This report records the first packed-geometry checkpoint. The [subsequent allocation and distance-transform investigation](allocation-and-distance-transform.md) measures Android allocation bytes and counts, diagnoses the GC countertrend, rejects the row-ring experiment, and adds exact contour/geometry improvements. Physical-device GPU/thermal profiling and cold shader compilation remain open work. Neither checkpoint adds a public API trade-off.
