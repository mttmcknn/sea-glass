# Long-list capture helpers

These helpers capture a real, scrolling 288-row sample. They never build or install APKs. Use an isolated checkout and a device assigned to your task; preserve other developers' devices and worktrees. Recording and builds must be outside timing windows.

## Build matched variants

Baseline `23905e9` contains the same list scene with renderer `e9d405f`. Candidate `975c48d` adds the bounded segmented LRU. Apply `baseline-diagnostics.patch` only to the baseline and `candidate-diagnostics.patch` only to the candidate. These are temporary local profiling changes; do not ship them. Both add identical runtime/cache/native instrumentation, adapted to each cache implementation. Builders and rendering behavior are unchanged by the patches.

With JDK 17 and the Android SDK configured, build each isolated variant:

```sh
./gradlew :androidApp:assembleDemoRelease -Psigning.useGpgCmd=false --offline --no-daemon --no-parallel
```

Copy each `androidApp/build/outputs/apk/demoRelease/androidApp-demoRelease.apk` to a unique evidence path. Retain its SHA-256, commit, scene source hash, diagnostic patch hash and install result. Install the frozen APK and run the device command `cmd package compile -m speed -f dev.mttmcknn.seaglass.sample` before measuring it.

Launch `dev.mttmcknn.seaglass.sample/dev.mttmcknn.seaglass.android.MainActivity` with `--es seaglass.scene long-list --ez seaglass.profile true`. Verify the actual viewport, density, list bounds and gesture coordinates. Copy `config.template.json`, enter those observations, and set `coordinates_verified` only after inspection. The reference capture used 1080×2400, 420 dpi, x540 and y2100↔600; another device must be checked independently.

```sh
python3 tools/android-profile/long-list/prepare.py \
  --config /absolute/evidence/session-config.json \
  --apk baseline=/absolute/evidence/baseline.apk \
  --apk candidate=/absolute/evidence/candidate.apk \
  --identity 'baseline=23905e9 plus matched diagnostics; record source hashes' \
  --identity 'candidate=975c48d plus matched diagnostics; record source hashes' \
  --output /absolute/evidence/prepared
```

The manifest and generated configuration refuse overwrite. Push the four shell helpers and generated `device-config.sh` to `/data/local/tmp/seaglass-scroll/`. Push the three Perfetto configurations to **`/data/misc/perfetto-configs/seaglass-scroll-{cold,scroll,idle}.pbtxt`**; Perfetto cannot read configuration files in `/data/local/tmp` on the reference device.

## Timing and retention

Invoke the device shell through your device tooling:

```sh
sh /data/local/tmp/seaglass-scroll/capture-block.sh UNIQUE_LABEL /data/local/tmp/seaglass-scroll/device-config.sh
```

A block force-stops and launches the app once, then captures cold/down/revisit/idle phases in about 31 seconds. It verifies PID continuity and runtime counters, records all gesture events and complete gfxinfo text, and writes `/data/local/tmp/seaglass-scroll/blocks/UNIQUE_LABEL`. Pull the directory immediately. It refuses an existing label. If an action's outcome is uncertain, inspect and preserve partial results before attempting another label.

For retention, keep the same process alive and supply its verified PID:

```sh
sh /data/local/tmp/seaglass-scroll/capture-retention.sh UNIQUE_CYCLE_LABEL /data/local/tmp/seaglass-scroll/device-config.sh VERIFIED_PID
```

Each call performs one down/revisit/settle cycle and writes runtime/meminfo probes under `/data/local/tmp/seaglass-scroll/retention/`. It does not launch, reset, force GC or trace. Repeat with unique labels and the same PID; record whether the process was already warmed. Six cycles are repeated observations from one process, not six independent samples. Gauges cannot prove native-object liveness or GPU-memory retention.

## Analyze and verify images

```sh
python3 tools/android-profile/long-list/analyze.py /absolute/evidence/blocks/LABEL1 /absolute/evidence/blocks/LABEL2 \
  --repo /absolute/sea-glass-checkout \
  --trace-processor /absolute/trace_processor_shell \
  --manifest /absolute/evidence/prepared/manifest.json \
  --output /absolute/evidence/comparison.json
```

The analyzer scopes traces to verified PIDs, checks gesture counts, actual list movement, scene/density/configuration identity and trace health, and retains frame, CPU, allocation/GC and memory metrics. Inspect every flag and endpoint comparison before aggregation. Equal input gestures do not guarantee identical offsets or exposed rows. Keep process CPU and scheduled CPU separate. Do not treat shader compilation as GPU execution or sum nested stages as total CPU.

For additional RenderThread attribution after validating the captures:

```sh
python3 tools/android-profile/long-list/render-cost.py /absolute/evidence/blocks/LABEL1 /absolute/evidence/blocks/LABEL2 \
  --trace-processor /absolute/trace_processor_shell \
  --output /absolute/evidence/render-attribution
```

This offline helper intersects slices with scheduled-running intervals, subtracts direct child work for exclusive CPU, and saves queries, raw CSV, category totals and matched-frame details. It checks incomplete slices, scheduler overlap and negative partitions. Inspect its flags and the full analyzer's results. Use `--phases down` for one phase; the default is down plus revisit. GPU execution and blur/shadow cost cannot be isolated from these generic CPU labels. See the [render-cost report](../../../docs/performance/render-cost-investigation.md) and [rejected experiment patches](render-cost-experiments/README.md) for the measured limits of upload changes.

`visual-checkpoints.sh` makes separate top/down/revisit captures outside timing. For pixel comparisons, use the same exact list item and offset, wait for ripples/animations to finish, and exclude only identified system UI such as the clock. Keep original image/video bytes. Screen-recorded frame rates are not unrecorded benchmark results.

Desktop checks and exact PNG comparison:

```sh
./gradlew :desktopApp:verifyLongList -PlongListOutput=/absolute/evidence/desktop-baseline
./gradlew :desktopApp:verifyLongList -PlongListOutput=/absolute/evidence/desktop-candidate -PlongListReferences=/absolute/evidence/desktop-baseline
```

See [the measured report](../../../docs/performance/long-list-scrolling.md) for identities, results, limitations and the reference run's actual chronology.
