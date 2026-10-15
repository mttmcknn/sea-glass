# SeaGlass Android emulator profiling — October 14, 2026

This file retains two successive optimization rounds. The [matched review fixes](#matched-review-fixes--final-quiet-capture-set) section contains the latest measurements.

The optimized release reduces Tumble's UI work and makes warm example switches substantially cheaper without changing the sampled geometry, shader equations, or F16 pixel values. Tumble still exceeds frame deadlines in this emulator workload; this is an improvement, not a claim of steady 60 fps.

## Measured results

Values below are the median of three matched runs, not pooled percentiles. UI work is the main thread's `Choreographer#doFrame` duration for material-changing frames. Slider completion p95 is `FrameCompleted - IntendedVsync` from valid gfxinfo rows; it includes scheduling and rendering and is not input-to-display latency.

| Workload / metric | Before | After |
| --- | ---: | ---: |
| Tumble: UI work per changed frame | 24.34 ms | 20.89 ms |
| Tumble: geometry per changed frame | 17.43 ms | 14.99 ms |
| Tumble: texture preparation per changed frame | 3.13 ms | 2.16 ms |
| Tumble: frame completion p95 | 60.71 ms | 53.98 ms |
| Tumble: completion after frame deadline | 48.08% | 31.03% |
| Warm example switch: UI work per transition | 28.29 ms | 9.32 ms |
| Warm example switch: geometry per transition | 13.41 ms | 0.00 ms |
| Warm example switch: texture preparation per transition | 2.47 ms | 1.66 ms |
| Frost: frame completion p95 | 14.75 ms | 14.91 ms |
| Light angle: frame completion p95 | 10.22 ms | 9.86 ms |

Tumble's UI work fell 14.2%, texture preparation fell 30.9%, and missed deadlines fell by 17.0 percentage points. Final per-run deadline rates were 35.09%, 30.00%, and 31.03%, versus 48.08%, 48.00%, and 49.02%. The shared geometry cache had no hits during these continuous Tumble drags; the improvement comes from cheaper generation and packing.

Warm example switches used 67.1% less UI work. All 72 geometry results across the 12 measured transitions were reused. This is a CPU-work improvement, not a claim that every switch presents on time. Driver/SurfaceFlinger timing and dropped frames remain in the raw FrameTimeline evidence.

Frost and light angle perform no geometry generation or texture preparation during their drags. Frost's final first run had one deadline miss (0.47 ms past its deadline) and p95 21.06 ms; the other two runs had zero misses and p95 14.91/14.65 ms. All light-angle runs had zero misses. The small median differences do not establish an improvement or regression for these already inexpensive controls.

The first baseline Music visit additionally spent 97.3 ms in native shader compilation, contributing to a roughly 140 ms FrameTimeline outlier. Warm runs had no such compilation. These changes do not eliminate first-use driver compilation.

## Changes

- Each live surface reuses its seeded cloud field during Tumble/shape edits. Size, scale and seed invalidate it. One retained entry is capped at 262,144 floats; larger rounded fields bypass retention.
- A shared nonblocking LRU reuses immutable geometry across example visits. It retains at most 32 entries and 8 MiB of estimated CPU storage, including textures, gradients and contour overhead. Contention bypasses caching; builders run outside the lock. Transient builders and GPU textures are separate from this bound.
- Android packs F16 data into reusable shader-owned `ShortArray` / `ShortBuffer` staging, capped at 2 MiB and released on disposal. Every published bitmap remains independent. Native row stride, zero padding and `Half.toHalf` conversion are preserved.
- CPU trace markers identify geometry, texture preparation, runtime program creation, ground recording and draw submission. Only the sample's optimized `demoRelease` manifest enables shell profiling.

## Method

Android API 36 arm64 emulator, AVD `Minimap_Plugin_Demo_20261009`, 60 Hz, Android Emulator OpenGL ES Translator on Apple M3 Max. The profiling instance was emulator-5602. Both builds used R8/resource shrinking, were non-debuggable and shell-profileable, and were compiled with `cmd package compile -m speed -f`. No builds or correctness tests ran during the timed recordings.

The viewport was fixed at 1600 × 2400 px, density 320 (800 × 1200 dp), so the seven Components surfaces remained visible beside the sliders. Each slider workload was the same pair of 600 ms drags, with a 250 ms gap and Reset beforehand. Frost used y=1221, Tumble y=1755, and light angle y=2174 after scrolling the independent controls pane to the bottom. Each switch workload selected Shape study → Components → Music player → Components, with fixed pauses. Warmup runs preceded final captures and were excluded.

Reported baseline captures: Tumble/Frost 4–6, light 1–3, and warm switch 2–4. Final captures: 1–3 for each workload. Initial Tumble run 1 had kernel ftrace loss and was excluded; the ftrace buffer was enlarged before the reported baselines. Selected runs have no ftrace data loss or incomplete measured slices. Unsupported optional ftrace event notices are retained in the analysis.

Perfetto stage slices and Android FrameTimeline were recorded alongside gfxinfo. Gfxinfo's finite ring truncated the longer switch captures, so switch CPU results use trace-linked scene-change frames instead. FrameTimeline classifications and gfxinfo deadline comparisons use different clocks/definitions and must not be conflated. Native `shader_compile` slices establish the cold/warm distinction.

## Validation and delivery

- 43 JVM tests passed, including exact cached/uncached geometry, LRU bounds/eviction, concurrent builders and cache invalidation.
- All five desktop interaction groups passed, covering sliders, palettes, Material You, responsive layouts and example/preview persistence.
- Three tests ran on the Android emulator and passed: raw F16 byte equivalence against the old uploader, earlier-bitmap independence, scratch bounds/release and padded-stride reuse.
- The final static Components/Reset app-area screenshot exactly matched the cloud-cache intermediate build (which preserves the original geometry). Status/navigation bars were excluded.
- Optimized Android release build, R8 and vital lint passed. The original nine web tests had passed earlier; no web code changed in this follow-up.

The baseline for this follow-up is the local Material You implementation before these three optimizations, built on upstream commit `99652ee89e3ca82f6b2d314436c8fc880aa038f8`. APK hashes and selected raw traces are in the evidence archive. The full source patch includes the earlier Material You work and this profiling follow-up. Changes remain local and uncommitted on `codex/material-you-performance`.

The final APK is installed and the emulator has been restored to 1080 × 2424 px / 420 dpi. Physical devices, thermal behavior, older-Android fallback performance and iOS have not been profiled here.

## Matched review fixes — final quiet capture set

Tumble’s instrumented UI frame median fell from 20.90 to 6.05 ms (71.1%), and p95 from 25.35 to 9.95 ms (60.8%). Geometry work runs on background threads during these drags. Every surface completes more visual updates, and all final requested Tumble revisions reach presentation. Settling after the final input takes longer, and occasional deadline misses remain.

The baseline here is the already optimized cache/uploader build above. Both builds received speed AOT compilation and matching Tumble/switch warmups. Final results use `review-before-*-7..9` and `review-after-*-1..3`, with three runs for each of four workloads. No Gradle work, correctness tests, or trace analysis ran during these recordings. Earlier review runs were excluded because of input-tail overlap or competing host work.

| Workload | Instrumented UI p50, before → after | Instrumented UI p95, before → after | gfx deadline misses, before → after |
|---|---:|---:|---:|
| Tumble | 20.90 → 6.05 ms | 25.35 → 9.95 ms | 55/170 (32.35%) → 7/219 (3.20%) |
| Frost | 2.74 → 3.06 ms | 4.30 → 4.39 ms | 0/219 (0.00%) → 2/222 (0.90%) |
| Light | 2.89 → 2.77 ms | 4.06 → 4.01 ms | 0/219 (0.00%) → 0/219 (0.00%) |
| Switch | 10.98 → 11.17 ms | 14.00 → 12.31 ms | 2/100 (2.00%) → 6/102 (5.88%) |

UI values are medians of three per-run percentiles. The deadline column combines missed/eligible gfxinfo counts across those runs. Switch gfxinfo contains a smaller retained main-window population than the complete trace, including popup work.

Across all numbered UI frames, Tumble p50/p95 changes from 20.35/25.04 to 5.91/9.95 ms. Across upload-containing frames it changes from 20.90/25.35 to 6.07/9.95 ms. Candidate UI Geometry slices are zero; background Geometry records 475–489 builds and 895–943 ms of worker wall time per run. Worker time includes descheduling and is reported separately from UI-frame work.

The candidate completes 68–72 upload frames and 422–444 surface uploads per run, versus 54 frames and 378 uploads before. Every individual surface applies 56–67 revisions, versus 54 before. Unique Request/Applied markers show that all seven final Tumble surface revisions were drawn and presented in all six drags. Intermediate revisions can be coalesced, but no final Tumble revision is missing.

The smoother frame sequence adds final settling delay. From the original injected last MOVE event to final upload presentation, the baseline proxy has a 60.10 ms median (57.88–69.31), while the candidate has 75.69 ms (60.17–80.63). Candidate presentation also matches the latest requested revision of every surface. The baseline has no revision markers, so its value remains a proxy. The approximately 15.59 ms increase is roughly one 60 Hz interval. ACTION_UP-to-settled medians are 40.07 and 68.20 ms. These are injected-input emulator timings, not physical touchscreen latency. App-dispatch timestamps alone exaggerate the MOVE difference because the baseline input queue is slower.

Candidate Tumble UI p95 is below 16.67 ms, but two of 217 instrumented UI frames exceed that interval (19.50 and 17.62 ms). Gfxinfo still has 7/219 deadline misses, while app FrameTimeline has 6/219 App Deadline Missed rows; these definitions differ. Frost and Light perform no geometry generation or uploads during drags, and these three-run samples do not establish a general change in their already small costs. Warm switches reuse all geometry in both builds, retain occasional deadline misses, and show similar median UI cost.

All 72 final scene-switch revisions have Applied markers. Fifty-four have presentation matches in that same frame. The two initial scene-selection frames containing the other 18 revisions were marked Dropped Frame, so the one-time markers cannot establish their later presentation time. These missing timestamps are retained rather than counted as successes. [Perfetto's documentation](https://perfetto.dev/docs/data-sources/frametimeline) distinguishes app GPU/post completion from SurfaceFlinger presentation.

The analysis separates background and UI geometry, includes all numbered UI frames, instrumented frames, and upload frames, and excludes nested resynced diagnostic spans from frame counts. Primary windows include the scripted gestures and one second after final ACTION_UP; full-capture statistics are retained separately. All 24 final traces have the expected inputs, no incomplete numbered UI frames, and no data-loss flags. The same 37 ftrace setup notices remain recorded in each trace.

Validation logs for this review fix record 61 passing JVM tests, all eight passing desktop interaction groups, and successful optimized Android assembly with R8 and vital lint. The paired static Reset screenshots have identical RGB and alpha in app crop `(0,144)–(1600,2304)`; this does not compare every animation frame. The evidence package includes the 24-pair CSV, complete JSON, report, APK hashes, and raw traces. Baseline APK SHA-256 is `89ed661983e5776d4dc63778f14c23bbb7854a8b477750706f5ceb5484c65a15`; candidate is `7b939ab2b4806f2441706798fcdcb523456d541911ebacf2dbc4315d3dc2ecc2`.

These are emulator measurements on the setup above. Physical-device, thermal, older-Android CPU fallback, and iOS performance remain unmeasured.

## Reproduce the controlled workload

The capture helpers in `tools/android-profile` deliberately use coordinates for the documented two-pane viewport. Inspect the screen before running them; use the Components example, controls at the top, Material You off and Reference/Mix selected. Both builds need matching stage trace markers and a shell-profileable optimized manifest. The revised renderer also adds Request/Applied revision markers; the earlier baseline has no revision markers, so its final-upload latency remains a proxy.

```sh
# Build and install the desired revision, then prepare the fixed viewport.
./gradlew :androidApp:assembleDemoRelease
adb install -r androidApp/build/outputs/apk/demoRelease/androidApp-demoRelease.apk
adb shell cmd package compile -m speed -f dev.mttmcknn.seaglass.sample
adb shell wm size 1600x2400
adb shell wm density 320
adb shell am start -n dev.mttmcknn.seaglass.sample/dev.mttmcknn.seaglass.android.MainActivity
adb shell mkdir -p /data/local/tmp/seaglass-profile /data/misc/perfetto-configs
adb push tools/android-profile/config.pbtxt /data/misc/perfetto-configs/seaglass.pbtxt
adb push tools/android-profile/switch-config.pbtxt /data/misc/perfetto-configs/seaglass-switch.pbtxt
adb push tools/android-profile/slider.sh /data/local/tmp/seaglass-profile/slider.sh
adb push tools/android-profile/switch.sh /data/local/tmp/seaglass-profile/switch.sh

# Run a warmup, then three measured repetitions with distinct labels.
adb shell sh /data/local/tmp/seaglass-profile/slider.sh final-tumble-1 1755
adb shell sh /data/local/tmp/seaglass-profile/slider.sh final-frost-1 1221
# For light angle, scroll only the controls pane to the bottom and verify y=2174.
adb shell sh /data/local/tmp/seaglass-profile/slider.sh final-light-1 2174
# Return to Components and warm all switch destinations before measuring.
adb shell sh /data/local/tmp/seaglass-profile/switch.sh final-switch-1

# Pull each matching pair; retain baseline/final labels.
adb pull /data/misc/perfetto-traces/seaglass-final-tumble-1.perfetto-trace captures/final-tumble-1.perfetto-trace
adb pull /data/local/tmp/seaglass-profile/final-tumble-1.gfxinfo captures/final-tumble-1.gfxinfo
python3 tools/android-profile/analyze_review_profiles.py captures --pattern "final-*.perfetto-trace" --name final-analysis --trace-processor /absolute/path/to/trace_processor_shell

# Restore normal emulator dimensions when finished.
adb shell wm size reset
adb shell wm density reset
```

Analysis used Perfetto trace_processor v58.2. Install the official binary for the host and pass its path explicitly. Select a single device with `adb -s SERIAL` when more than one is connected. The scripts reset the sample recipe and collect system traces; use a development emulator. Keep builds/tests separate from timed runs.

The helpers wait for each timed trace to finish before another Reset can enter its tail. The revision-aware analyzer separates worker geometry from UI frames, exposes coalesced requests, and joins applied revisions to SurfaceFlinger presentation when available. Keep three baseline and three candidate captures per workload; compare identical warmup and input sequences.
