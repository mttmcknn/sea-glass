# Performance hill climb

The default renderer on **Android 13+ and Skia now draws the complete material in the component's draw pass**, using a runtime body shader and native ground blur. It removes the asynchronous flat-fill-to-bitmap transition. Most recipe changes update shader uniforms; only size, density, shape, tumble and seed rebuild the small smooth geometry field. This implements the new rendering path; **near-instant response on a real GPU remains unverified** because no Android device is available in this environment.

The available headless Skia backend executes the shader in software. It is **slower than the cached CPU bitmap implementation in this harness**, not evidence of a GPU speedup. The material is complete when drawn, but first-paint measurements still include Compose dispatch, drawing and readback. Neither completion on the first material draw nor a changed-image count establishes device FPS.

The earlier exact-CPU optimization phase remains useful historical evidence: its measured kernel workloads became **1.17–2.67× faster**, and all 336 measured pixel checksums stayed identical. Those numbers describe the reference CPU renderer, **not the new default shader**.

The baseline is commit [`116bab1`](https://github.com/mttmcknn/sea-glass/commit/116bab131654b667f8f6e9567ecae54d54f5e27e), before the performance changes. Raw data is retained alongside this report:

- [Exact CPU baseline](renderer-baseline.json), [first CPU iteration](renderer-iteration-1.json), and [optimized exact CPU renderer](renderer-final.json).
- [Four-box CPU compatibility renderer](renderer-fast-ground.json).
- [CPU playground baseline](ui-baseline.csv), [optimized CPU renderer with cancellation](ui-optimized-cancellation.csv), and [optimized CPU renderer with conflation](ui-conflated.csv).
- [CPU bitmap first-paint run](first-paint-cpu.csv), [live shader iteration 1 first-paint run](first-paint-live.csv), and [live shader iteration 1 software UI run](ui-live-software.csv).
- [Final live shader software UI run](ui-live-final-software.csv).

## Current live renderer: evidence and limits

Android 13+ uses `RuntimeShader`; Skia uses `RuntimeEffect`. The contour renders as a native vector path, smooth shape information comes from small cached textures, and the body evaluates lighting and grain at display resolution. Native graphics layers blur the fixed downward cast shadow, contact shadow and transmitted light. The first actual material draw produces the complete component instead of publishing a flat fill and replacing it later.

This changes the microscopic grain pattern and uses platform blur. The four body fixtures have mean absolute RGB differences of **0.68–2.00 levels out of 255** versus the exact CPU body over white; this is a mean, not a maximum error. The [rendering guide](../rendering.md#exactness-and-platform-differences) gives the per-fixture mean, p95 and maximum and separates body quality from ground-layer differences. Exact CPU checksum parity does not apply to this shader.

Android 7–12L uses the asynchronous CPU compatibility path with conflated requests and a four-box ground approximation. Twelve ground fixtures measured less than **0.18/255 RMS** and approximately **1.2/255 maximum** channel difference over white against exact Gaussian ground. The exact Gaussian path remains the test and benchmark reference.

The first-paint measurements and initial live UI dataset below record **shader iteration 1**. The final shader retains an analytic micro-gradient optimization, reducing its grain hash evaluations from 32 to 16 per fragment, and moves light-dependent constants into uniforms. Its final body-quality checks remain at approximately 0.68–2.00/255 mean absolute RGB difference. First-paint timings have not been rerun for this final shader; the separate final three-round UI run is reported below.

First-paint testing uses separate reference and measurement processes, distinct seeds, cold insertion and repeat visits, at 520×1000 and density 1. It samples after real Compose semantic selection and requires exact equality to the settled output of that renderer. In the live iteration 1 run, **28 of 42 immediate sampled frames were already exact**. The other 14 samples arrived before Compose dispatched the newly inserted content and contained an empty affected region; they are not evidence of partially shaded glass, but they also mean **not all 42 first event frames were complete**. The CPU bitmap run had zero exact immediate frames. Dedicated first-material-draw checks distinguish rendering completeness from event dispatch.

Median time from scene selection to the settled exact frame, including software drawing/readback, across three rounds:

| Scene | CPU bitmap cold | Live iteration 1 cold | CPU bitmap revisit | Live iteration 1 revisit |
| --- | ---: | ---: | ---: | ---: |
| Music player | 64.70 ms | 114.40 ms | 45.49 ms | 90.39 ms |
| Phone call | 59.92 ms | 112.74 ms | 33.31 ms | 93.42 ms |
| Email | 74.40 ms | 167.05 ms | 50.96 ms | 125.91 ms |
| AI chat | 79.64 ms | 150.26 ms | 40.10 ms | 111.59 ms |

These software scene measurements are regressions in elapsed time. They neither establish nor refute the intended hardware-accelerated benefit. A real Android GPU run is still needed to assess cold shader compilation, first appearance, slider latency, frame times, memory and sustained behavior.

The separate live playground runs show the cost of software shader evaluation. The final analytic-gradient/uniform changes reduced measured median final-update latency by approximately 4–8% versus shader iteration 1, but both shader runs remain slower than the CPU bitmap path in this software harness. Median final-update latency and the median of the final run's three per-round pump p95 values are:

| Slider | CPU + conflation latency | Live iteration 1 latency | Final live latency | Final live pump p95 |
| --- | ---: | ---: | ---: | ---: |
| Tumble | 54.98 ms | 679.76 ms | 627.45 ms | 332.95 ms |
| Frost | 35.06 ms | 614.08 ms | 587.75 ms | 313.32 ms |
| Surface light angle | 24.30 ms | 630.20 ms | 601.98 ms | 326.42 ms |
| Lift | 25.00 ms | 452.14 ms | 414.17 ms | 306.15 ms |

Every live burst produced 24 distinct sampled images, but each software draw/readback was slow and the bursts expanded to roughly 6–8 seconds. They did **not** sustain the intended 16 ms update schedule. Final-update latency includes the last draw and completion readback; these values cannot be read as GPU frame times.

Cold-composition medians from the two live UI runs, separate from the first-paint harness above:

| Scene | Live iteration 1 software | Final live software |
| --- | ---: | ---: |
| Music player | 113.74 ms | 114.26 ms |
| Phone call | 107.53 ms | 103.33 ms |
| Email | 160.87 ms | 151.10 ms |
| AI chat | 159.16 ms | 149.15 ms |

Three scenes improved modestly in this small sample; Music was approximately unchanged and slightly slower. These results support the specific software hill-climb iteration, not a claim of universal or near-instant rendering performance.

## Phase 1: exact CPU changes

The initial Java Flight Recorder profile attributed approximately **75% of sampled renderer time to Gaussian blur**. The first iteration limited convolution to the mask's actual support, reused scratch arrays, and rearranged the vertical pass for contiguous access. Ground-dependent workloads improved approximately **1.3–2.5×**, so that change was retained. Light-only updates, which reuse the ground, were essentially unchanged in that iteration.

The next iteration applied contiguous access to both convolution passes, reduced repeated compositing calculations, and cached the shaded body. Each output pixel still accumulates Gaussian taps in the original order. The existing kernel, falloff, texture, optical calculations, contour and sampling quality remain unchanged.

The exact CPU reference retains the following changes. Its cached bitmap pipeline also underpins the older-Android compatibility path; these are not the live shader's allocation or timing characteristics:

- **Bounded Gaussian work:** skip regions outside the nonzero mask and its full blur support. Retain transparent image boundaries and the complete kernel.
- **Reusable ground scratch:** the three-layer ground stage now allocates six full-image `DoubleArray`s instead of thirteen. This is about **54% less full-image double-array allocation within that stage**, not a measurement of whole-app RAM. Small kernel arrays are additional.
- **Compositing by column:** calculate horizontal coordinates once per output column, using `O(width)` scratch space. Exact integer lookups can read the body directly; fractional locations retain premultiplied bilinear sampling and the original rounding.
- **One cached shaded body:** elevation-only updates reuse body pixels. This deliberately retains another **four bytes per body pixel** per renderer, excluding object overhead. Each cache holds one entry. Ink-only and unused palette-alpha changes no longer invalidate material pixels.
- **A sequential, conflated material worker:** finish the current full-quality render, then render the latest pending request. Intermediate requests do not build a queue or repeatedly cancel work before it becomes visible. Leaving composition still cancels the worker.

## Phase 1: exact CPU renderer measurements

These runs used Linux amd64, OpenJDK **17.0.20.1**, eight available processors, a 256 MB initial/1 GB maximum JVM heap, and **2× sampling**. Every CPU run used JFR `settings=profile`; the final run was measured alone, without concurrent builds or other benchmarks.

Each case has **24 measured samples**, **eight per-case warmups**, and an initial all-path warmup lasting at least **three seconds**. A cold case creates a new renderer, not a new JVM. A warm case retains its renderer and changes the named setting. The scene has seven separately cached components. Timers separate CPU rendering from native bitmap conversion; pixel hashing and bitmap readback occur outside both timers. This does not measure GPU upload or Android frames.

The comparison script validates workload settings, ordered cases, fixture dimensions, component counts and every pixel checksum before calculating statistics from raw timings. All **336 exact-CPU checksums match**. Times below are milliseconds; speedup compares medians. P95 uses the nearest-rank sample. The seven-component tumble case fell from 545.88 ms to 255.74 ms. This table does not benchmark the live shader or the approximate-ground compatibility setting.

| Case | Before median | After median | Before p95 | After p95 | Speedup |
| --- | ---: | ---: | ---: | ---: | ---: |
| cold.button | 130.35 | 80.95 | 146.49 | 99.35 | 1.61× |
| cold.toolbar | 175.63 | 107.51 | 199.37 | 119.01 | 1.63× |
| cold.disc | 41.08 | 15.39 | 61.41 | 19.23 | 2.67× |
| warm.button.frost | 108.27 | 57.81 | 122.96 | 65.51 | 1.87× |
| warm.button.grain | 17.74 | 13.84 | 21.70 | 15.34 | 1.28× |
| warm.button.tumble | 120.73 | 75.62 | 135.71 | 88.46 | 1.60× |
| warm.button.bulge | 103.45 | 58.53 | 112.70 | 67.50 | 1.77× |
| warm.button.thickness | 102.03 | 57.62 | 121.51 | 62.94 | 1.77× |
| warm.button.elevation | 104.46 | 52.19 | 125.64 | 56.43 | 2.00× |
| warm.button.transmission | 103.27 | 57.21 | 111.13 | 64.18 | 1.81× |
| warm.button.light | 12.59 | 10.78 | 13.36 | 11.44 | 1.17× |
| warm.button.palette | 101.59 | 59.94 | 110.56 | 63.43 | 1.70× |
| warm.scene.light | 47.06 | 36.87 | 56.23 | 43.72 | 1.28× |
| warm.scene.tumble | 545.88 | 255.74 | 588.78 | 281.88 | 2.13× |

## Phase 1: CPU playground interaction measurements

The real desktop Compose playground was exercised through slider semantics at density 1. Each burst sends **24 updates approximately 16 ms apart**. Completion requires the visible preview to match an independently settled reference exactly. The cold scene cases start a new composition within the warmed process.

The three rounds include composition, raster drawing and bitmap readback. Completion polls can add up to approximately 4 ms. These are small-sample interaction proxies, not device FPS measurements. `distinct_preview_frames` counts differing images sampled during the burst, including the first sampled image; it is not the number of display frames or necessarily 24 distinct completed material states.

Median latency from the last slider update to its final pixels:

| Slider | Baseline CPU | Optimized CPU + cancellation | Optimized CPU + conflation |
| --- | ---: | ---: | ---: |
| Tumble | 67.18 ms | 42.96 ms | 54.98 ms |
| Frost | 56.75 ms | 40.02 ms | 35.06 ms |
| Surface light angle | 28.04 ms | 24.14 ms | 24.30 ms |
| Lift | 43.66 ms | 25.89 ms | 25.00 ms |

Conflation is an explicit tradeoff: Tumble's median final-update latency increased from **42.96 ms to 54.98 ms** versus the optimized cancel-on-every-update implementation. In exchange, the burst produced **23–24 distinct sampled preview images**, up from **5–20** with cancellation and **1–3** in the baseline. A completed render can briefly show an intermediate setting before the latest one finishes; the final image still matches the requested material exactly.

Median cold composition-to-final latency:

| Scene | Baseline CPU | Optimized CPU + cancellation | Optimized CPU + conflation |
| --- | ---: | ---: | ---: |
| Music player | 74.71 ms | 73.04 ms | 66.65 ms |
| Phone call | 56.14 ms | 54.07 ms | 60.90 ms |
| Email | 84.76 ms | 77.23 ms | 75.05 ms |
| AI chat | 100.12 ms | 92.21 ms | 85.09 ms |

Cold scene results are **not uniformly better**: Phone call increased from **56.14 ms to 60.90 ms** in this three-round proxy. No universal startup-speed claim follows from these measurements.

## Phase 2: faster CPU compatibility ground

The older-Android fallback replaces exact separable Gaussian ground blur with four running-sum box passes. It retains the exact CPU body equations and cached-body behavior. This is an intentional approximation: its output is assessed through visual-error tests, not exact checksum equality. Across the twelve ground fixture/style combinations, error over white stayed below 0.18/255 RMS and approximately 1.2/255 maximum per channel.

The [fallback run](renderer-fast-ground.json) used the same Linux/JDK setup, 2× sampling, 24 measured samples, eight warmups and JFR profiling as the original baseline. Ordered cases, dimensions and component counts match. Its `workloadVersion` is **2**, identifying the four-box approximation; the strict comparison tool intentionally rejects comparison with exact workload version 1. The table below compares timing only and makes no pixel-equality claim.

| Case | Original CPU median | Fallback median | Original CPU p95 | Fallback p95 | Median speedup |
| --- | ---: | ---: | ---: | ---: | ---: |
| cold.button | 130.35 ms | 46.22 ms | 146.49 ms | 49.76 ms | 2.82× |
| cold.toolbar | 175.63 ms | 66.44 ms | 199.37 ms | 75.83 ms | 2.64× |
| cold.disc | 41.08 ms | 8.32 ms | 61.41 ms | 10.21 ms | 4.94× |
| warm.button.frost | 108.27 ms | 25.16 ms | 122.96 ms | 31.27 ms | 4.30× |
| warm.button.grain | 17.74 ms | 13.74 ms | 21.70 ms | 14.84 ms | 1.29× |
| warm.button.tumble | 120.73 ms | 43.95 ms | 135.71 ms | 50.89 ms | 2.75× |
| warm.button.bulge | 103.45 ms | 26.10 ms | 112.70 ms | 32.19 ms | 3.96× |
| warm.button.thickness | 102.03 ms | 25.39 ms | 121.51 ms | 30.11 ms | 4.02× |
| warm.button.elevation | 104.46 ms | 14.24 ms | 125.64 ms | 18.39 ms | 7.34× |
| warm.button.transmission | 103.27 ms | 25.56 ms | 111.13 ms | 30.74 ms | 4.04× |
| warm.button.light | 12.59 ms | 11.82 ms | 13.36 ms | 17.80 ms | 1.06× |
| warm.button.palette | 101.59 ms | 26.46 ms | 110.56 ms | 31.44 ms | 3.84× |
| warm.scene.light | 47.06 ms | 37.13 ms | 56.23 ms | 42.21 ms | 1.27× |
| warm.scene.tumble | 545.88 ms | 160.63 ms | 588.78 ms | 173.92 ms | 3.40× |

The cold button improved 2.82×, the disc 4.94×, and the seven-component tumble update 3.40× in these CPU measurements. Light-only updates already reuse the ground: their median benefit is small and the single-button p95 increased from 13.36 ms to 17.80 ms. These are JVM kernel measurements, not Android device timings, and do not describe the live shader's speed.

## Reproduce

Run from the repository root with JDK 17. Run benchmarks separately from tests, screenshot capture and builds so those processes do not compete for CPU. Use the same sample counts, warmups and profiling setting for both revisions. `benchmarkRenderer` measures the exact CPU reference, even when the application uses the live shader.

```bash
./gradlew -PseaGlass.android=false \
  -PbenchmarkSamples=24 -PbenchmarkWarmups=8 \
  -PbenchmarkOutput=/absolute/path/before.json \
  -PbenchmarkProfile=/absolute/path/before.jfr \
  :sea-glass:benchmarkRenderer
```

After changing revisions, run the same command with `after.json` and `after.jfr`, then compare:

```bash
python3 tools/compare-benchmarks.py /absolute/path/before.json /absolute/path/after.json
```

The earlier baseline revision predates the harness; use the same benchmark source and task wiring when reproducing that revision. Do not mix a changed workload with the baseline results. `-PbenchmarkFastGround=true` selects the approximate CPU ground path and records a distinct workload version; it requires the visual-error checks and cannot be passed off as exact checksum parity.

Run the separate Compose interaction benchmark. This uses the current default backend, so today's Skia run exercises the live shader in software rather than reproducing the historical CPU tables above:

```bash
./gradlew -PseaGlass.android=false \
  -PbenchmarkRounds=3 -PbenchmarkMode=all \
  :desktopApp:benchmarkPlayground
```

The task prints CSV rows after its description and CSV header. `benchmarkMode=sliders` and `benchmarkMode=scenes` are available for focused runs. Keep raw rounds, not just the medians.

Generate first-paint references, then measure in a separate process with the same directory:

```bash
./gradlew -PseaGlass.android=false \
  -PfirstPaintMode=reference -PfirstPaintRounds=3 \
  -PfirstPaintDirectory=/absolute/path/first-paint-references \
  :desktopApp:benchmarkFirstPaint

./gradlew -PseaGlass.android=false \
  -PfirstPaintMode=measure -PfirstPaintRounds=3 \
  -PfirstPaintDirectory=/absolute/path/first-paint-references \
  :desktopApp:benchmarkFirstPaint
```

Run correctness checks after timing is finished:

```bash
./gradlew -PseaGlass.android=false :sea-glass:jvmTest :desktopApp:verifyPlayground
```

## Validation and Android build

The exact CPU benchmark checksums preserve all measured reference output pixels. The regression suite additionally compares ground rendering and compositing against a frozen original implementation across fractional dimensions/scales, all contour types, alpha patterns, valid setting extremes and a large toolbar. Cache transitions, ignored palette fields and cancellation recovery are covered. Separate live-renderer tests exercise shader input precision and body quality; these do not assert exact CPU pixel equality.

**Final validation passed:** all 28 tests completed with zero failures or skips; all three playground interaction checks passed; and the Android `demoRelease` build completed with R8 optimization and vital lint. The app-interface and tablet screenshots were regenerated from the final shader. Shader screenshots intentionally differ from the CPU originals, with their material quality assessed separately rather than by exact CPU pixel equality.

The Android sample is configured as **0.1.3** with an installable `demoRelease` build: R8 optimization and resource shrinking enabled, non-debuggable, signed with the sample's existing debug key so it can update the prior playground APK. The debug signing key is for this sample distribution. Applications use their own release signing configuration.

```bash
./gradlew :androidApp:assembleDemoRelease
```

The APK is written to `androidApp/build/outputs/apk/demoRelease/androidApp-demoRelease.apk`. Device frame timing, thermal behavior and memory measurements remain separate follow-up measurements; this report makes no on-device performance claim.
