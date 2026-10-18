# Long-list scrolling: bounded geometry reuse

The long-list follow-up reduces repeated geometry construction and allocation without changing material pixels or increasing the cache budget. It does **not** establish faster overall scrolling, lower jank, or faster startup.

Measured locally on 2026-10-18, against the already optimized `e9d405f` renderer. The identical list scene is in `2cb76ab` (verification correction in `23905e9`); the cache change is `975c48d`. No release, tag, remote CI, push or publication was performed.

## Workload and environment

The playground's **Long list** entry opens a `LazyColumn` of 288 stable-key rows across six component families: notes, listening controls, action capsules, walks, pebble selectors and reminders. Each family has real accessible actions. Three quarters of the rows use repeated seed/size recipes; one quarter use unique seeds. Actual geometry keys also depend on measured dimensions, including text wrapping and one-pixel weight rounding. Tumble remains fixed at 0.025. There is no clock, network or random dataset input.

Android launch: `--es seaglass.scene long-list`. Passive position diagnostics are off by default and can be enabled with `--ez seaglass.profile true` for profiling. They do not drive rendering.

Measurements used an owned API 36 arm64 Android emulator on macOS, 1080×2400 at 420 dpi, a minified/profileable `demoRelease` APK and `cmd package compile -m speed -f`. Fingerprint: `google/sdk_gphone64_arm64/emu64a:16/BE2A.250530.026.D1/13818094:user/release-keys`. Two other emulators were in use by other chats; their workloads were not controlled. No builds, recording or analysis ran during timing captures. No physical Android device was connected. GPU execution, mobile power and thermal behavior were not measurable here; render-thread CPU and shader-compilation CPU are not GPU execution time.

Each fresh-process block contains a 6-second cold trace, an 8-second forward phase, an 8-second reverse phase and a 4-second idle trace. Each scrolling phase injects four 400 ms drags and two 120 ms flings. Coordinates and real movement were inspected. Primary forward endpoints were item 55, with offsets 41–232 px; reverse endpoints were item 0, offsets 0–244 px. Roughly 59 rows were exposed, not all 288. Gesture timing and partial-row exposure vary slightly, so these are matched scenarios rather than identical frame trajectories. Desktop verification separately visits the final row.

Primary order was baseline-4, candidate-1, candidate-2, baseline-5. Six candidate memory cycles occurred between candidate-2 and baseline-5. Six baseline memory cycles followed baseline-5. Memory cycles continued the warmed primary process, used no forced GC, and ended at item 0/offset 0. They are repeated observations from one process per variant, not six independent devices or processes.

## What was expensive

The exploratory baseline attributed about 85% of scheduled app CPU during scrolling to RenderThread. Command flushing and buffer swapping dominated; UI `postAndWait` frequently waited on that work. One 142.8 ms UI outlier coincided with a RenderThread text-atlas operation. Geometry construction was about 3.6% of app CPU, and native texture upload about 0.2%. A new native bitmap cache was therefore not added.

The existing geometry LRU repeatedly evicted reused small shapes when large or unique cards entered the list. The change uses probation and protected queues under the **same shared 4 MiB / 32-entry total**. First use is cached immediately. Hits earn protection; its target is 75% of bytes and entries, with older protected entries demoted when needed. Eviction prefers probation. Large entries can borrow capacity, and entries exceeding the total budget still bypass retention. Builders remain outside the nonblocking mutex; generation invalidation and concurrent-winner behavior are preserved.

There are no renderer pools, native caches, caller-managed caches, shader changes, sampled-quality reductions or public library API changes. Geometry keys, F16 data and deterministic construction remain unchanged.

## Measured results

Primary round-trip medians, two runs per variant. MB below means decimal million bytes.

| Measure | Baseline | Candidate | Change |
|---|---:|---:|---:|
| Geometry builds | 124 | 92 | −25.8% |
| ART allocated bytes | 212.43 MB | 202.58 MB | −4.64% |
| Geometry scheduled CPU | 352.34 ms | 333.41 ms | −5.37% |
| Whole-process CPU | 9,556.5 ms | 9,544 ms | Essentially unchanged |
| GC collections | 17 | 17 | Unchanged |
| Peak geometry-cache bytes | 4,173,496 | 4,173,496 | Unchanged |
| Peak cache entries | 11 | 16 | Below the same 32-entry bound |

FrameTimeline app-deadline misses were **38/1,249 (3.04%) → 44/1,242 (3.54%)**. Gfxinfo misses were **4/477 → 4/478**; its ring contains only about 120 recent frames per phase. UI p95 results were mixed. These results do not support a jank or frame-rate improvement claim.

Cold runs built five geometries and applied all six initial material revisions in one presented frame for both variants. Median maximum material request-to-presentation latency was **143.13 → 142.66 ms**, with overlapping ranges. The request marker follows initial synchronous geometry preparation, so this is not complete launch time. Maximum cold UI frame medians were **186.40 → 192.05 ms** with wide candidate variation. There is no startup improvement claim. Idle had zero geometry builds, uploads, UI frames or GC, and about 1 ms of process CPU including observer work in both variants.

Six additional warm round trips gave median allocations of **201.94 → 183.61 MB per cycle (−9.08%)**, with median GC collections **16 → 15**. Endpoint gauges were:

| Gauge | Baseline median | Candidate median |
|---|---:|---:|
| Native heap allocated | 33.86 MB | 31.03 MB |
| Java heap used | 24.20 MB | 14.96 MB |
| PSS | 90,690 KiB | 78,501 KiB |
| Malloced bitmaps | 65 / 9,363 KiB | 44 / 7,790 KiB |

Both sequences fluctuated within a bounded range; native allocation changed by only +23,472 / +44,496 bytes from cycle one to six. Natural GC, framework caches and dead-but-uncollected objects affect these gauges. This is not retained-object analysis, proof against leaks, or actual GPU-memory accounting. The CPU geometry cache's bound excludes active renderer inputs and framework-owned resources.

## Validation and evidence

- 78 JVM tests passed, including exact keys/output, immediate A→B→A reuse, scan resistance, byte/entry demotion, large entries, clear, disabled/single-entry budgets, concurrent publication and throwing builders.
- Existing desktop playground checks passed for Tumble, palette, Material You, resizing, preview state, keyboard focus/activation and disabled controls.
- Long-list verification passed all six action families, forward/reverse scrolling, disposal/revisit state, bounded mounted semantics, final row, Back and Top.
- Six desktop checkpoint PNGs matched baseline exactly. Android's settled 1080×2400 capture matched exactly over `(0,100)–(1080,2400)`; only the changing system status bar was excluded. An earlier mismatch was solely the Top button's tap ripple and was retained as a rejected comparison.
- Clean production JVM tests, Android assembly and iOS simulator compilation passed after removing temporary instrumentation. iOS runtime and physical Android performance were not measured.
- Perfetto captured no data loss or incomplete relevant slices. Its 37 setup notices were unavailable vendor GPU events, not missing scheduler events. Scheduled CPU and Android process CPU differ by about 3.9%; both are retained separately without speculative correction.

The local evidence directory is `../evidence/scroll-followup/` relative to this checkout. It contains frozen APKs/source identities, all raw traces and sidecars, repeated-cycle dumps, the aggregate script and JSON, exact-image checks, original PNG/MP4 captures and Library delivery identities. Primary files are `analysis/primary-aggregate.json`, `analysis/retention-aggregate.json`, `analysis/aggregate-summary.txt` and `media/android-pixel-comparison.json`. Raw evidence is intentionally outside Git.

The reproducible capture/analyzer helpers and matched temporary patches are in [`tools/android-profile/long-list`](../../tools/android-profile/long-list/README.md). The temporary profiling patches are absent from production source. The candidate patch was checked to reconstruct every measured source file exactly.

The remaining substantial work is render-thread/GPU investigation on representative physical Android hardware. This change's supported benefit is less geometry work and allocation at the same visual output and cache budget.

The subsequent [render-cost investigation](render-cost-investigation.md) separates repeated RenderThread uploads from UI-side input creation and records two rejected remedies. It adds no further production optimization.
