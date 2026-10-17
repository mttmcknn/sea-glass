# Follow-up evidence and reproduction

The measured production source is `c617b88` on local branch `codex/performance-followup`, based on packed checkpoint `7b9274e` and original release `c0ebad4`. Later documentation commits do not change the measured renderer. The [report](../allocation-and-distance-transform.md) distinguishes original, checkpoint, selected final changes, and rejected experiments.

## Evidence included here

- `jvm-comparison/`: both-round and pooled kernel statistics, exactness checks, allocation deltas and payload accounting. Each case has 120 observations per variant.
- `first-paint-analysis/`: all 84 software-render/readback observations, per-fixture summaries, and the 21 frozen-reference hashes.
- `android/analysis/`: all 12 main runs, 24 gesture endpoints, three separate object-count diagnostics, and six fresh-process startup observations. `final-followup-summary.json` omits repeated detailed timed-snapshot metadata; its source hash identifies the complete local JSON.
- `android/manifest.json`: exact profiling APK hashes, helper hashes, six process blocks and capture order. `device-context.json` records the owned API 36 arm64 emulator and elapsed capture times. Main captures took 224.221 seconds; main plus startup captures took 270.742 seconds. The emulator was stopped after verification.
- `android/visual-comparison.json`: exact reset-screen comparison within the application crop; system bars are excluded.
- Validation logs: 73 JVM tests, eight desktop interaction/accessibility groups, five Android uploader tests, iOS source compilation, and a successful Android build after removing diagnostics.
- `protection-check.json`: original and release checkouts remained clean at `c0ebad4`; the separate README edits remain present. The prior media source was not modified.

The sample-only runtime diagnostic hook was identical in all measured APKs and has been removed from every checkout. Its patch remains at [`tools/android-profile/runtime-stats.patch`](../../../tools/android-profile/runtime-stats.patch) for reproducing approximate process counters. Deprecated object counting was enabled only in separate diagnostic captures. The clean production APK is not the instrumented APK whose hash appears in the measurement manifest.

## Local raw artifacts

The task workspace is `/Users/mmckenna/Documents/Codex/2026-10-17/task-2`. Its `evidence/` directory retains the large raw files outside Git:

| Directory | Contents |
|---|---|
| `final-followup/{checkpoint,performance}/` | Four 60-sample kernel JSONs, JFR profiles, build/measurement logs, frozen final diagnostic APK |
| `final-followup/{baseline,performance}/first-paint.log` | Both first-paint measurement logs |
| `references/` | The 21 original-release RGBA references |
| `final-followup/android/{blocks,cold}/` | Unmodified device artifacts, PID/completion markers, Perfetto traces and snapshots |
| `final-followup/android/analysis/` | Full trace-query output, count diagnostics, PID-scoped startup analysis and detailed findings |
| `gc-followup/` | Allocation/GC diagnosis and rejected row-ring experiment; its `performance` label is **not** the final candidate |
| `contour-followup/` | Storage-only JVM comparison and preserved snapshot-test failures/repair evidence |
| `basis-followup/` | Rejected bounded-cache comparison; `android/incomplete-session/` is excluded partial data from an ended emulator session |

The final comparison does not pool data from different emulator sessions. Both baseline and final startup captures use fresh app processes, but existing OS/driver caches were not cleared. Two startup traces retained transient process names; independently verified PIDs select their telemetry. The raw PID provenance is included under `android/`.

## Reproduce kernel and first-paint measurements

Use separate local checkouts at the revisions being compared, JDK 17, and the same installed dependencies. Keep builds, reference generation, trace processing, and other benchmarks out of each timing window. These commands describe the recorded local offline runs; offline mode requires already-cached dependencies.

```sh
# Run inside each checkout, alternating checkpoint and final for two rounds.
./gradlew :sea-glass:benchmarkLiveGeometry -PseaGlass.android=false \
  -PbenchmarkSamples=60 -PbenchmarkWarmups=30 \
  -PbenchmarkOutput=/absolute/output/VARIANT/live-geometry-ROUND.json \
  -PbenchmarkProfile=/absolute/output/VARIANT/live-geometry-ROUND.jfr \
  -Psigning.useGpgCmd=false --offline --no-daemon --no-parallel

# After all timing has ended; this validates canonical output as well as metrics.
python3 tools/compare-live-geometry.py /absolute/output \
  --before checkpoint --after performance --expected-samples 60 --expected-warmups 30

# Generate references only on the original release; reuse them for both variants.
./gradlew :desktopApp:benchmarkFirstPaint -PseaGlass.android=false \
  -PfirstPaintMode=reference -PfirstPaintRounds=3 \
  -PfirstPaintDirectory=/absolute/references --offline --no-daemon --no-parallel
./gradlew :desktopApp:benchmarkFirstPaint -PseaGlass.android=false \
  -PfirstPaintMode=measure -PfirstPaintRounds=3 \
  -PfirstPaintDirectory=/absolute/references --offline --no-daemon --no-parallel
python3 tools/analyze-first-paint.py /absolute/output \
  --before baseline --after performance --reference-dir /absolute/references
```

Retain each first-paint Gradle log as `VARIANT/first-paint.log`. The first-paint analyzer validates 42 unique fixture/seed/cache rows per variant and all reference hashes; the harness throws if a final image fails exact equality. The task's exact execution script is `evidence/final-followup/run-jvm.sh`.

## Reproduce Android capture and analysis

Use an exclusively owned device, with the sample's Components screen inspected at 1600 × 2400 pixels and 320 dpi, Reference/Mix selected, and Material You off. The fixed coordinates in `android/capture/` are valid only for that inspected layout. The measurement used optimized profileable APKs with speed AOT, installed from frozen copies.

The six blocks are B1 C1 F1 F2 C2 B2. Each later block installs its APK and invokes `am force-stop` and `am start -W` separately; the initial block is freshly launched. The capture helper performs Reset and a one-second settle before every workload. No extra two-second launch wait was used. Each block has one warmup and two timed drags; each variant's first block additionally records idle and counting-enabled diagnostics. Each drag pair is 600 ms right, 250 ms pause, 600 ms left. Runtime snapshots and traces have deliberately different windows, recorded in the report.

The portable device scripts and six-second Perfetto configuration are preserved under `android/capture/`. The task's guarded Android Emulator plugin orchestration is `evidence/final-followup/android/plugin-capture.js`; it checks tool results and pulls each block before the next install. Cold launches use `capture-cold.sh` separately in B1 F1 F2 B2 B3 F3 order. Do not combine warmup/count/cold records with ordinary interaction medians.

The existing [`tools/android-profile/`](../../../tools/android-profile/) analyzers inspect frames and requested/applied material revisions. Task-local `evidence/basis-followup/{flatten_blocks.py,analyze_variants.py,summarize_counts.py}` and `evidence/analyze_additional.py` retain the additional allocation, scheduler and startup analysis. Run trace processing only after timing stops. The final cold query uses verified PID scoping, not transient process names. Complete local analysis commands are also recorded in the task's capture README and analysis artifacts.

## Test snapshot delivery and iOS prerequisites

Before the test-only AWT notification fix, headless renderer tests intermittently observed stale pixels or a missing expected seed. The failure XML, pinned Compose delivery investigation and patch remain in `evidence/contour-followup/snapshot-pump/`; [the diagnosis](snapshot-pump.md) summarizes them. Assertions were not weakened, and no retries, extra frames or forced redraws were added. The final complete JVM suite passed once after combining the selected changes.

The earlier release's six iOS tests executed successfully; final source compilation also passes. Current simulator execution remains blocked on the changed Xcode/CoreSimulator setup described in [ios-reconciliation.json](ios-reconciliation.json). No license acceptance, first-launch setup, security change or runtime installation was performed. This task neither published a release nor triggered remote CI.
