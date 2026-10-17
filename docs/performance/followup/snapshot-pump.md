# Headless renderer snapshot-delivery correction

Only `sea-glass/src/jvmTest/kotlin/dev/mttmcknn/seaglass/internal/LiveMaterialRendererUpdateTest.kt` was changed in the contour candidate for this correction. No production changes, assertion removals, relaxed comparisons, forced invalidations, additional captured frames, delays, or retries were added.

## Evidence and mechanism

The preceding full run contained 67 tests and one failure: the intermediate-completion test expected worker seeds `[3200013, 3200019]`, but observed `[3200013, 3200017]`. The final settings write had not caused its draw callback before the test captured that frame. Earlier failures reported by the renderer audit also included a skipped first pending seed and an already-published worker completion with stale pixels. Focused renderer-only processes passed, which alone did not resolve the ordering problem.

The latest failing renderer XML and the unmodified test source are preserved under `pre-fix/`. Earlier failures remain in the contour clone's `build/contour-verification/` evidence.

The pinned Compose UI 1.10.3 bytecode was inspected locally; `compose-scene-bytecode.txt` preserves the relevant classes. `GlobalSnapshotManager.desktop` gets the AWT `MainUIDispatcher`, independent of the test's `TestCoroutineScheduler`. `BaseComposeScene.render` drains queued snapshot commands at render entry, then renders; its internal snapshot flush does not wait for an apply observer already executing on the separate AWT thread.

The previous helper drained scheduled coroutines and sent apply notifications from the test thread after selected frames. `MaterialScene.render()` itself did not pump pending caller writes before readback. Sending from the test thread also did not establish an ordering barrier with an in-flight global apply on AWT.

The revised helper drains the test scheduler, synchronously sends apply notifications on the AWT event thread (waiting behind pending AWT work), then drains scheduled continuations. It runs before each requested material frame and at the existing explicit worker-delivery points. The actual production snapshot observer must still invalidate drawing; the helper does not request a redraw. Captures and exact-pixel/coherence/queue assertions are unchanged.

## Verification

Offline JDK 17.0.18 on this Mac, sequential runs after the edit:

1. Focused `LiveMaterialRendererUpdateTest`: **6 tests passed**, no failures, errors, or skips. Log `focused-fixed.log`, XML `focused-fixed.xml`.
2. Full `:sea-glass:jvmTest`: **67 tests passed**, no failures, errors, or skips. Log `full-fixed.log`, all XML preserved under `full-fixed-results/`.

The full run was executed once after the fix. Existing contour parity tests and all other JVM assertions remained enabled. `git diff --check` also passed. This validates the revised test run; it does not establish physical-device frame performance or prove the absence of all possible test flakes.

Commands used:

```sh
JAVA_HOME=/Users/mmckenna/.gradle/jdks/eclipse_adoptium-17-aarch64-os_x.2/jdk-17.0.18+8/Contents/Home \
ANDROID_HOME=/Users/mmckenna/Library/Android/sdk \
./gradlew :sea-glass:jvmTest \
  --tests dev.mttmcknn.seaglass.internal.LiveMaterialRendererUpdateTest \
  -PseaGlass.android=false -Psigning.useGpgCmd=false --offline --no-daemon --no-parallel

# Full run: same command without the --tests filter.
```

`test-harness.patch` contains only this test change and can be applied to checkpoint/baseline clones for identical verification. The existing Gradle cache needed its normal writable wrapper lock; the authorized runs used sandbox escalation for that lock. No remote CI or network dependency resolution ran.
