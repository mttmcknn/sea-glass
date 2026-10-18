# Render-cost investigation: two rejected upload changes

This follow-up found repeated RenderThread texture uploads, but neither tested remedy met the acceptance criteria. Immutable CPU inputs added work without removing uploads. Hardware-backed inputs changed rendered edge pixels. Both experiments were reverted. Production sources remain identical to `f0cff307ee7093d74d12c855fe9ea929fa85004e`, including the bounded geometry-cache improvement described in [the preceding report](long-list-scrolling.md).

Measured locally on 2026-10-18. This is a negative result with reproducible evidence, not an additional scrolling-speed improvement. No push, remote CI, release, tag or publication was performed.

## Environment and method

The workload remains the deterministic 288-row `coastal-288-v1` list, six component families, 1080×2400 at 420 dpi. About 59 rows were exposed during each round trip. The API 36 arm64 emulator reported `Google (Apple), Android Emulator OpenGL ES Translator (Apple M3 Max), OpenGL ES 3.0 (4.1 Metal - 91.7)` and Skia OpenGL. No physical Android device was connected. Two other emulators belonged to other chats; their workloads were not controlled or disturbed.

The immutable-input experiment used one emulator session and the order **baseline-1, immutable-1, immutable-2, baseline-2**. Each frozen, minified/profileable APK was installed and AOT-compiled with `cmd package compile -m speed -f`. Each fresh-process block captured 6 seconds cold, 8 seconds forward, 8 seconds reverse and 4 seconds idle. Each scroll phase used four 400 ms drags and two 120 ms flings. There were no builds, recordings or heavy analyses during capture. Forward endpoints were item 55 at offsets 62, 213, 99 and 56 px; reverse endpoints were item 0 at offsets 0, 108, 40 and 0 px. These are matched workloads, not identical frame trajectories.

The existing analyzer found no validation flags. Perfetto reported the same 37 setup notices for unavailable vendor events; relevant scheduler and completed RenderThread slices were available. CPU attribution intersects slices with scheduled-running intervals and subtracts direct-child intersections to obtain exclusive CPU. No negative exclusive intervals, overlapping scheduler intervals or incomplete RenderThread slices were found. Less than 0.1% of RenderThread scheduled CPU was outside completed root slices in each scrolling phase. Nested inclusive measures must not be added together.

Hardware correctness checks used a second emulator session after the first session ended, with the same AVD, viewport and backend. No timing results were pooled across sessions, and no hardware-candidate timing or retention cycles were run after visual parity failed.

## What the traces establish

Reanalysis of the two preceding cache-candidate round trips attributed **85.74% of scheduled app CPU to RenderThread**. Its round-trip median was 8,509.95 ms. The major exclusive categories were:

| RenderThread category | Scheduled CPU per round trip |
|---|---:|
| Buffer swapping | 2,123.85 ms |
| Command flushing | 1,873.91 ms |
| Drawing | 1,452.32 ms |
| Draw-op execution | 841.88 ms |
| Texture uploads | 781.21 ms |

Texture uploads accounted for 9.18% of RenderThread CPU and 7.87% of app CPU, with a median 16,367.5 upload events. Of these, 13,407 events / 641.23 ms occurred in matched frames without a new UI `SeaGlass.TextureUpload` marker. Consecutive frames contained repeated 364×260 uploads with new GPU texture IDs. This supports investigating resource re-upload, but those IDs identify GPU resources rather than public Bitmap owners; not every upload can be attributed to SeaGlass from this trace alone.

The earlier report's roughly 0.2% upload figure measured the **UI-side material input creation**, not these separate RenderThread uploads. Shader compilation consumed only 28.75 ms inclusive CPU per round trip, below 0.4% of RenderThread CPU. That does not measure shader execution on the GPU. Neither shadow nor blur execution has an isolated trace label here, and `flush layers` is not a complete blur-cost measure.

One new baseline gfxinfo dump reported 104.70 MB of scratch render targets across 65 entries, 2.00 MB of scratch textures and 108.76 MB total GPU cache usage. This makes resource pressure a plausible hypothesis. A snapshot does not establish that eviction caused the repeated uploads, attribute all scratch resources to SeaGlass, or measure physical-device VRAM. Buffer swap and flush CPU likewise cannot be translated into mobile GPU utilization or power.

## Experiment 1: immutable CPU inputs — rejected

The first experiment copied the existing RGBA_F16 bitmap to an immutable RGBA_F16 bitmap, then recycled the unpublished staging bitmap. It retained the exact existing data, dimensions, color space and premultiplication metadata. Five Android uploader tests passed, including byte equivalence, retained-input independence and bounded scratch behavior. No new cache was added.

Round-trip medians from two runs per variant, against `f0cff30` with identical temporary diagnostics:

| Measure | Baseline | Immutable input |
|---|---:|---:|
| RenderThread upload events | 16,447 | 16,608 |
| RenderThread upload CPU | 775.96 ms | 789.69 ms |
| UI material upload CPU | 19.54 ms | 43.55 ms |
| RenderThread scheduled CPU | 8,322.96 ms | 8,449.98 ms |
| App scheduled CPU | 9,750.52 ms | 9,933.62 ms |
| Android process CPU | 9,379.5 ms | 9,563 ms |
| Geometry builds | 92 | 92 |
| ART allocated bytes | 202.63 MB | 202.82 MB |
| GC collections | 17 | 17 |

UI upload CPU increased **122.85%** while repeated RenderThread uploads persisted. In frames with no new UI upload, RenderThread upload CPU was 636.26 → 649.51 ms. Immutability alone did not resolve the mechanism on this backend.

FrameTimeline deadline misses were 38/1,258 → 34/1,252; gfxinfo misses were 1/478 → 3/477. The small, mixed differences, two repeats and slight trajectory differences do not establish improved frame timing. Gfxinfo retains only a recent-frame ring. Cold maximum material request-to-presentation medians were 133.34 → 137.67 ms; maximum cold UI frame medians were 171.53 → 168.45 ms. The request marker excludes initial synchronous geometry preparation, so it is not full launch time. There is no startup improvement claim. Idle produced no geometry builds, uploads, UI frames or GC; process CPU medians were 2.5 → 0.5 ms over the four-second window, including observer work.

Endpoint native heap allocation was around 31 MB for both variants. Malloced bitmap counts after idle were 45/51 for baseline and 55/55 for immutable. PSS ranges overlapped (75,071–78,613 versus 76,244–76,316 KiB). These gauges include GC and framework effects and do not establish a retention improvement. Additional warm retention cycles were unnecessary once the candidate failed its performance objective. No rejected cache or bitmap-retention policy remains in production.

## Experiment 2: hardware-backed F16 inputs — rejected

Android's hardware bitmap path offered a source-backed alternative to repeated CPU image uploads: copy the input to `Bitmap.Config.HARDWARE`, keep it only when the resulting hardware buffer reports `RGBA_FP16`, and otherwise retain the original CPU input. There were still only two current inputs per shader owner and no global cache. This is not a total GPU-memory bound: display lists, in-flight work and GC can extend old input lifetimes. Android's upload path also waits for completion, so any benefit would have required checking first appearance as well as scrolling. See Android's [HardwareBitmapUploader](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android16-release/libs/hwui/HardwareBitmapUploader.cpp) and Skia's [hardware-buffer image generator](https://raw.githubusercontent.com/aosp-mirror/platform_external_skia/android16-release/src/gpu/ganesh/GrAHardwareBufferImageGenerator.cpp).

The first readback test returned zero pixels because direct copying of the unpremultiplied hardware bitmap attempted an unsupported render target. Android logged `Unable to generate GPU buffer in a format compatible with the provided bitmap`. The diagnostic was corrected to use a separate premultiplied view of the same buffer, solely for readback; every fixture has alpha 1. Production pixels and metadata were unchanged. This behavior follows the readback path in [Android Bitmap.cpp](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android16-release/libs/hwui/hwui/Bitmap.cpp) and the alpha-type restriction in [Skia Device.cpp](https://raw.githubusercontent.com/aosp-mirror/platform_external_skia/android16-release/src/gpu/ganesh/Device.cpp).

With corrected readback, eight of nine Android tests passed: the original five tests plus finite production-range input pixels/metadata, prior-input independence and software-Canvas behavior. The deliberately out-of-contract adversarial half-float payload test still failed (76/1,224 bytes); it includes negative values, infinities and NaN payloads. Finite fixture success alone does not establish equivalent rendering.

The decisive screen comparison found **2,072 changed content pixels** along the green card's perimeter, bounding box `[64,706,1016,1384)`, maximum 8-bit channel difference 14. Other visible components and text matched. Captures were settled at item 0 / offset 0; only the system status bar above y100 was excluded. A second baseline capture after reinstall/relaunch matched baseline exactly, and a second candidate capture matched candidate exactly. The cross-variant difference is stable rather than a transient clock, scrollbar or animation. Its exact sampling/coverage cause remains unresolved.

This candidate failed the exact-visual gate and was reverted before performance or memory trials. No speed or memory claim is made for it.

## Preserved result and next evidence

All temporary instrumentation, experimental production code and experimental compiled tests were removed. Library, Android, desktop, sample and iOS source trees match `f0cff30`; no API, accessibility, Tumble, light, color or frost behavior changed. This branch adds only this report and reproducibility tools. The earlier checkpoint's passing JVM, desktop visual/accessibility, Android build and iOS simulator compile results still describe the unchanged production sources.

After restoration, Android `assembleDemoRelease` and `assembleAndroidDeviceTest` passed offline, replacing experimental local build outputs. The portable attribution helper matched all 11 saved result sections on a reference trace exactly (4,206.463414 ms RenderThread CPU; 8,132 uploads / 419.891352 ms; no flags). Both rejected patches were checked in disposable directories; diagnostics plus the hardware patch reconstructed all eight frozen tracked source files exactly. Independent review checked the report against raw evidence. No new JVM or iOS run was needed for unchanged production sources.

The next useful measurement needs a representative **physical Android device supporting API 33+**, with its model, OS and renderer recorded: repeat the same list protocol, capture GPU/driver counters where available, examine scratch-target pressure and uploads, and run repeated memory cycles. The emulator evidence cannot rank physical-device fragment execution, blur passes, bandwidth, energy or thermal cost reliably.

Reducing blur/shadow layers or shrinking filtered bounds would be a different, visual tradeoff experiment. Smaller bounds are not automatically pixel-equivalent: blur rescaling can depend on source bounds, as seen in [Skia's image-filter transforms](https://raw.githubusercontent.com/google/skia/80d088a/src/core/SkImageFilterTypes.cpp). Such a change was not silently introduced. Any future tradeoff should present measured performance and visible comparisons for a user decision.

## Reproduction and evidence

Raw evidence is local at `../evidence/render-cost/`, outside Git:

- `attribution/`: scheduler-overlap attribution of the preceding cache traces, queries, categories and repeated-upload examples.
- `android/blocks/rc-{baseline,immutable}-{1,2}/`: all four raw cold/scroll/idle traces, runtime counters, gestures, gfxinfo and memory dumps.
- `immutable-comparison.json`, `immutable-summary.json`, `immutable-analysis/`: complete analyzer output and independent RenderThread analysis.
- `baseline/`, `immutable-input/`, `hardware-input/`: frozen APKs, hashes, candidate patches, build logs, initial and corrected test source/results.
- `media/`: original baseline/candidate PNGs, same-variant repeat PNGs, exact comparison JSON and spatial difference attribution.
- `identities.json`, `primary-session.json`, `session-config.json`, `prepared/manifest.json`: checkpoint, source/patch/APK hashes and inspected device/capture configuration.

The baseline APK SHA-256 is `70a79dac16eb860b27bfc40d8df8088d4ee5594a9317fa7fc907d40756499835`; immutable is `1e36af4c7eb0fc47ac55ec0d098916c894aa0d0a1c6a7c8fd3c2ffca242b3ab3`; hardware is `e346b18e2158770cfa00696c5e6b6010666fa0595b6496419320a5d646394205`. All use checkpoint `f0cff30` plus the same `candidate-diagnostics.patch` (SHA-256 `59341e7d83a24fc050c1f55988be6e4d7a1751f02b0f6bbec3e363afb97e7cbb`) and their respective experiment patch.

The [capture guide](../../tools/android-profile/long-list/README.md) describes fresh-process timing and verification. [Rejected experiment patches](../../tools/android-profile/long-list/render-cost-experiments/README.md) reproduce these attempts in isolated checkouts. They are evidence, not recommended optimizations. The additional `render-cost.py` helper attributes exclusive RenderThread CPU without treating it as physical GPU execution.
