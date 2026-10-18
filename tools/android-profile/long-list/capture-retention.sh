#!/system/bin/sh
# One down/revisit/settle cycle in an ALREADY running long-list process.
# Args: unique-cycle-label, generated device config, expected process PID.
# No Perfetto, recording, launch, force-stop, GC request or app-state reset.
set -eu
label="$1"
config="$2"
expected_pid="$3"
case "$label" in *[!a-zA-Z0-9-]*|'') echo 'Invalid cycle label' >&2; exit 2;; esac
case "$expected_pid" in *[!0-9]*|'') echo 'Expected one numeric PID' >&2; exit 2;; esac
. "$config"
root=/data/local/tmp/seaglass-scroll
out="$root/retention/$label"
pkg=dev.mttmcknn.seaglass.sample
activity="$pkg/dev.mttmcknn.seaglass.android.MainActivity"
test ! -e "$out"
mkdir -p "$out"
cp "$config" "$out/device-config.sh"
echo "$expected_pid" > "$out/expected.pid"
date +%s > "$out/cycle.started"
read up rest < /proc/uptime
echo "$up" > "$out/cycle.started-uptime"
pidof "$pkg" > "$out/pid.before"
test "$(cat "$out/pid.before")" = "$expected_pid"

snapshot() {
  dumpsys activity "$activity" sg-runtime-stats > "$1"
  grep -q 'SEAGLASS_RUNTIME_STATS' "$1"
  grep -q '"allocationCountingRequested":false' "$1"
  grep -q '"scrollScene"' "$1"
  grep -Fq "$DATASET_ID" "$1"
}

# Place memory probes outside the runtime-counter bracket. These remain
# diagnostic snapshots; they neither force collection nor prove liveness.
dumpsys meminfo -a "$pkg" > "$out/meminfo.before"
snapshot "$out/runtime.before"
sh "$root/gesture.sh" "$config" down "$out/down.gestures"
sh "$root/gesture.sh" "$config" revisit "$out/revisit.gestures"
sleep "$MINIMUM_SETTLE_SECONDS"
snapshot "$out/runtime.after"
dumpsys meminfo -a "$pkg" > "$out/meminfo.after"
pidof "$pkg" > "$out/pid.after"
cmp -s "$out/pid.before" "$out/pid.after"
grep -q '"isScrollInProgress":false' "$out/runtime.after"
read up rest < /proc/uptime
echo "$up" > "$out/cycle.complete-uptime"
date +%s > "$out/cycle.complete"
echo "SEAGLASS_RETENTION_CYCLE_COMPLETE $label PID=$expected_pid"
