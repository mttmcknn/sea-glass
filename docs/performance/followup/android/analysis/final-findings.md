# Final Android emulator performance comparison

Final `performance` is c617b88 (packed texture + contour storage + EDT work; no basis cache). It reduces measured geometry CPU and allocation per build. The largest UI improvement remains the packed texture change. GC remains more frequent than original; first material presentation and shader work do not show a reliable cold-start improvement.

## Controlled sample

The six main blocks ran on one emulator instance `056baaec-c2b1-4288-b3ae-84732ad5f5e0`, started 2026-10-17 19:35:04 UTC, at 1600×2400 / 320 dpi. Order: B1, C1, F1, F2, C2, B2. Each block had a fresh speed-AOT app process, one warmup and two measured captures; all seven Components surfaces received two 600 ms Tumble drags with a 250 ms gap. Twelve main runs (four per variant) are separate from three idle and three allocation-count diagnostics. Main capture elapsed 224.221 s; main plus six cold launches 270.742 s, as reported by the capturing agent. No concurrent host analysis/build work occurred during capture.

[Manifest](../manifest.json) records APK/helper hashes and raw block timestamps/PIDs; [flattened inputs](../flattened-inputs.json) records copies and hashes while preserving raw blocks. Cold inputs and independently verified PIDs are in [cold inputs](../cold-inputs.json), [PID map](../cold-pids.json), and [PID provenance](../cold-pid-provenance.json). This final session is distinct from the earlier basis-cache ablation and does not pool its observations.

## Main medians

Medians are taken after each run is measured or normalized. UI p50/p95 are medians of each run’s instrumented-frame percentile. Endpoint Process CPU/ART counters span approximately 2.75 s; scheduler primary windows span first DOWN through last UP + 1 s (about 2.49 s). These accounting methods are not interchangeable.

| Metric | Baseline | Packed checkpoint | Final performance |
|---|---:|---:|---:|
| Completed geometry builds | 486.5 | 486.5 | 486.5 |
| ART allocated bytes / build | 936,170 | 763,809 | 748,239 |
| Geometry scheduled CPU ms / build | 1.1684 | 1.1384 | 1.0839 |
| Process CPU ms / build | 3.1862 | 3.1128 | 3.0493 |
| App scheduled CPU ms / build | 3.3418 | 3.2618 | 3.2088 |
| Texture scheduled CPU ms / full capture | 136.46 | 28.61 | 29.55 |
| UI frame p50 ms | 4.344 | 2.684 | 2.876 |
| UI frame p95 ms | 5.525 | 3.518 | 3.670 |
| Process CPU delta ms | 1,550.0 | 1,506.0 | 1,487.5 |
| App scheduled CPU ms / primary window | 1,625.71 | 1,581.64 | 1,562.07 |
| UI thread CPU ms / primary window | 310.71 | 199.01 | 205.27 |
| RenderThread CPU ms / primary window | 470.77 | 471.34 | 473.24 |
| HeapDaemon CPU ms / primary window | 199.15 | 279.73 | 270.03 |
| ART GC count | 23.0 | 35.0 | 35.0 |
| ART GC time ms | 203.0 | 279.0 | 266.0 |
| Compaction-pause sum ms | 5.953 | 8.942 | 8.416 |

Final versus original: allocated bytes/build −20.07%, geometry CPU/build −7.23%, Process CPU/build −4.30%, app scheduled CPU/build −3.98%, UI p50 −33.80%, UI p95 −33.57%, texture scheduled CPU −78.35%. Final versus packed: allocated bytes/build −2.04%, geometry CPU/build −4.79%, Process CPU/build −2.04%, app scheduled CPU/build −1.62%. Final UI p50/p95 are 7.14%/4.34% higher than packed in this set, so the incremental worker changes do not establish an additional UI-frame benefit.

Geometry CPU/build is lower in both reversed final blocks; ranges are baseline 1.1435–1.1801, packed 1.1303–1.1667, final 1.0792–1.0879 ms. Small whole-app changes remain descriptive observations from four runs, not statistical confidence or a physical-device claim. Observed build counts are 483–490, so cumulative bytes/CPU are normalized by each run’s actual completed geometry count. Whole-process ratios still include Compose, framework and observer work.

ART GC count is 23 for every original run, 35 for every packed run, and 35/35/35/34 for final. Final median GC time is 31.03% above original and 4.66% below packed. Perfetto GC counts agree with ART. No blocking ART GC occurs in measured endpoints, but background GC/compaction consumes time. One packed run has a 1.411 ms maximum compaction pause; final maximum is 0.514 ms. Lower bytes does not mean fewer collections.

## Raw main runs

| Run | Builds | Allocated MB | Bytes/build | Process CPU ms | Geometry CPU/build ms | GC count / ms | UI p50 / p95 ms |
|---|---:|---:|---:|---:|---:|---:|---:|
| baseline-tumble-1 | 490 | 458.695 | 936112 | 1538 | 1.1435 | 23 / 204 | 4.301 / 5.407 |
| baseline-tumble-2 | 490 | 458.715 | 936153 | 1551 | 1.1573 | 23 / 206 | 4.363 / 5.623 |
| baseline-tumble-3 | 483 | 452.178 | 936186 | 1567 | 1.1801 | 23 / 202 | 4.494 / 5.528 |
| baseline-tumble-4 | 483 | 452.309 | 936458 | 1549 | 1.1795 | 23 / 193 | 4.324 / 5.522 |
| checkpoint-tumble-1 | 490 | 374.018 | 763302 | 1546 | 1.1667 | 35 / 282 | 2.701 / 3.557 |
| checkpoint-tumble-2 | 483 | 368.906 | 763781 | 1505 | 1.1464 | 35 / 269 | 2.667 / 3.351 |
| checkpoint-tumble-3 | 490 | 374.280 | 763837 | 1507 | 1.1305 | 35 / 283 | 2.814 / 3.596 |
| checkpoint-tumble-4 | 483 | 368.947 | 763866 | 1502 | 1.1303 | 35 / 276 | 2.592 / 3.478 |
| performance-tumble-1 | 490 | 366.572 | 748105 | 1485 | 1.0792 | 35 / 266 | 2.901 / 3.491 |
| performance-tumble-2 | 483 | 361.464 | 748372 | 1490 | 1.0877 | 35 / 273 | 2.850 / 3.717 |
| performance-tumble-3 | 483 | 361.562 | 748576 | 1468 | 1.0801 | 35 / 264 | 2.970 / 3.624 |
| performance-tumble-4 | 490 | 366.547 | 748055 | 1499 | 1.0879 | 34 / 266 | 2.689 / 3.740 |

## Separate object and idle diagnostics

| Variant | Completed builds | Approximate objects | Objects/build | ART allocated bytes/build | ART GC count / ms |
|---|---:|---:|---:|---:|---:|
| baseline | 483 | 890,471 | 1843.63 | 936101 | 23 / 212 |
| checkpoint | 490 | 910,155 | 1857.46 | 763561 | 35 / 272 |
| performance | 483 | 581,986 | 1204.94 | 748169 | 34 / 264 |

Counting-enabled observations ran once per variant, separately from main timing. Final objects/build is 34.64% lower than original and 35.13% lower than packed. These deprecated approximate whole-process counts include framework/observer work, not exact kernel object types. The ART object counter is unavailable. Legacy GC delta is zero in all three despite real ART/Perfetto GC; that legacy zero cannot support a no-GC claim. All separate counting final cohorts presented seven surfaces. The baseline count run has 483 requests but 481 Applied markers, with both final cohorts complete; intermediate requests may be superseded.

All three idle captures span 3.24–3.26 s, allocate 20,528 bytes, consume 1 ms Process CPU and record zero GC. Observer cost is retained without subtraction. This is a no-animation idle observation, not an idle frame-rate benchmark.

Post-capture Java used heap medians are 25.844 / 12.296 / 12.913 MB (baseline / packed / final); PSS medians are 77,274 / 65,579.5 / 66,427 KiB. Native allocation medians are 25.046 / 23.202 / 23.246 MB. These collection/history-dependent gauges are distinct from cumulative allocation bytes and from retained object graphs. No leak or precise retained-size claim follows.

## Presentation and cold launch

All 24 main final gesture cohorts requested, applied and displayed all seven surfaces. Every main request also matched Applied/presentation. No trace loss, incomplete UI frame or incomplete geometry flags occur. Main FrameTimeline has one missed row for baseline, zero for packed, one for final; gfxinfo completion deadline misses are 5/294, 2/294, 1/294. These small counts and distinct denominators do not establish a jank-rate or FPS improvement.

Final-cohort last MOVE event → presentation medians are 43.449 / 43.147 / 43.190 ms, with maxima 57.884 / 43.950 / 58.125 ms. UP event → presentation medians are 33.837 / 34.236 / 34.127 ms. Final settling is essentially unchanged.

Cold launches are three separate force-stopped fresh processes per baseline/final variant, with existing speed AOT and emulator/driver state. The trace starts before launch; this is not a clean OS boot or cleared GPU cache.

| Cold metric, median of three | Baseline | Final performance |
|---|---:|---:|
| am start TotalTime ms | 283.000 | 272.000 |
| First material frame start → presentation ms | 205.106 | 194.826 |
| Initial first request → presentation ms | 160.772 | 153.956 |
| First material UI frame ms | 188.023 | 191.611 |
| Whole-trace app scheduled CPU ms | 248.316 | 249.730 |
| Geometry scheduled CPU ms | 10.986 | 9.327 |
| Texture scheduled CPU ms | 2.366 | 0.625 |
| shader_compile count | 29.000 | 29.000 |
| shader_compile wall ms | 113.682 | 113.714 |
| shader_compile scheduled CPU ms | 106.691 | 108.216 |

First-material frame presentation ranges overlap: baseline 191.715–206.644 ms and final 192.362–204.575 ms. The median moves by about 10 ms, while whole-app startup CPU and shader compilation do not improve. Three launches cannot establish a reliable faster startup. All six initial cohorts apply/present seven materials together in the first material frame; no separate delayed material revision is observed. Each first material frame is marked App Deadline Missed; gfxinfo’s flagged-frame exclusion must not be used to claim a perfect cold frame. Revision delivery is not pixel equality.

Two final cold traces retain stale Perfetto process names: cold1 `<pre-initialized>`, cold2 `zygote64`. Their expected app PIDs match both endpoint files and runtime dump. `analyze_review_by_pid.py` and `analyze_additional.py --pid-map` select these independently verified PIDs and recover seven geometry builds and 29 shader-compile slices in each. The current cold outputs contain this corrected scope; missing name-filter telemetry is not treated as zero.

| Cold run | Launch ms | Material frame → present ms | App CPU ms | Shader wall ms | Initial surfaces presented together |
|---|---:|---:|---:|---:|---:|
| baseline-cold-1 | 265 | 205.106 | 248.316 | 108.236 | 7 |
| baseline-cold-2 | 283 | 206.644 | 255.251 | 119.827 | 7 |
| baseline-cold-3 | 283 | 191.715 | 244.038 | 113.682 | 7 |
| performance-cold-1 | 274 | 192.362 | 249.730 | 113.714 | 7 |
| performance-cold-2 | 272 | 194.826 | 242.835 | 112.595 | 7 |
| performance-cold-3 | 259 | 204.575 | 255.769 | 124.527 | 7 |

No cold loss/incomplete flags occur. Warm main captures contain no shader compile events; all cold captures contain 29. No GPU slices or GPU counter tracks were captured. CPU shader, texture and flush measurements are not GPU execution time. This evidence establishes no physical Android, iOS, energy, thermal, scrolling, or universal startup result. Reset screenshot equivalence and broader deterministic/API/accessibility tests remain separate evidence recorded by the parent task.

## Deliverables and reproduction

[Main run CSV](final-followup-per-run.csv), [gesture CSV](final-followup-gestures.csv), [compact main comparison JSON](final-followup-summary.json), [count CSV](final-followup-count-summary.csv), [cold CSV](final-cold-per-run.csv), and [cold summary JSON](final-cold-summary.json) retain the individual observations. Detailed review/additional JSON retains frame populations, scheduler/stage measurements and trace health.

Use `evidence/basis-followup/flatten_blocks.py --variants baseline checkpoint performance`, the repository review analyzer (main and count patterns separately), `evidence/analyze_additional.py`, `evidence/basis-followup/analyze_variants.py --variants baseline checkpoint performance --runs 4`, and `evidence/basis-followup/summarize_counts.py`. For cold review use `evidence/analyze_review_by_pid.py --pid-map evidence/final-followup/android/cold-pids.json`, the additional analyzer with the same map, then `evidence/summarize_cold.py --label final-cold`. The raw six cold directories and all main blocks remain preserved.
