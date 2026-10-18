# Rejected render-cost experiments

These patches preserve negative experiments from [the report](../../../../docs/performance/render-cost-investigation.md). Neither belongs in production. Apply **one at a time**, only in a disposable checkout at `f0cff307ee7093d74d12c855fe9ea929fa85004e`:

```sh
git apply /path/to/candidate-diagnostics.patch
git apply /path/to/immutable-input.rejected.patch
```

For the hardware experiment, substitute `hardware-input.rejected.patch`. The baseline uses only `candidate-diagnostics.patch`. Use the [capture guide](../README.md) to freeze source/APK identities, inspect the device and capture matched variants. Build test APKs with `:sea-glass:assembleAndroidDeviceTest`; the test package is `dev.mttmcknn.seaglass.test` and runner is `androidx.test.runner.AndroidJUnitRunner`.

- **Immutable:** five `AndroidFloatTextureUploaderTest` tests passed. Repeated uploads persisted, and UI upload CPU more than doubled in the matched trial.
- **Hardware:** the preserved patch contains the corrected diagnostic readback. Five original tests and three hardware tests passed; the adversarial half-payload test fails on the measured backend. More importantly, settled material-edge pixels differ, so the candidate was rejected before timing. Do not weaken that visual gate to claim an optimization.

The hardware test's separate premultiplied readback view avoids Android's failure to read an unpremultiplied hardware bitmap into an unsupported target. It does not mutate production input pixels or metadata. Readback fixtures use alpha 1. Passing finite fixtures is insufficient to establish equivalent GPU sampling or rendered output.

Neither patch adds a global cache. Hardware-buffer ownership is nevertheless not a strict process-wide residency bound because display lists, in-flight work and GC may retain prior inputs. No hardware-candidate memory or speed benefit was established.
