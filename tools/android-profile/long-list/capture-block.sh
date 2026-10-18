#!/system/bin/sh
# Called by root through its device plugin. No build/install/recording here.
# Args: unique-block-label, generated config path. Frozen APK already installed/AOT.
set -eu
label="$1"
config="$2"
case "$label" in *[!a-zA-Z0-9-]*|'') echo 'Invalid label' >&2; exit 2;; esac
. "$config"
root=/data/local/tmp/seaglass-scroll
out="$root/blocks/$label"
pkg=dev.mttmcknn.seaglass.sample
activity="$pkg/dev.mttmcknn.seaglass.android.MainActivity"
test ! -e "$out"
mkdir -p "$out"
cp "$config" "$out/device-config.sh"
date +%s > "$out/block.started"
read block_up rest < /proc/uptime
echo "$block_up" > "$out/block.started-uptime"
wm size > "$out/device-size.txt"
wm density > "$out/device-density.txt"
getprop ro.build.fingerprint > "$out/device-fingerprint.txt"

uptime_seconds() { read value rest < /proc/uptime; echo "${value%%.*}"; }
snapshot() {
  path="$1"
  dumpsys activity "$activity" sg-runtime-stats > "$path"
  grep -q 'SEAGLASS_RUNTIME_STATS' "$path"
  grep -q '"allocationCountingRequested":false' "$path"
  grep -q '"scrollScene"' "$path"
  grep -Fq "$DATASET_ID" "$path"
}
start_trace() {
  phase="$1"
  duration="$2"
  kind="$3"
  prefix="$out/$label-$phase"
  trace="/data/misc/perfetto-traces/seaglass-scroll-$label-$phase.perfetto-trace"
  test ! -e "$trace"
  read up rest < /proc/uptime
  echo "$up" > "$prefix.trace-requested-uptime"
  perfetto --background-wait --txt -c "/data/misc/perfetto-configs/seaglass-scroll-$kind.pbtxt" -o "$trace" > "$prefix.perfetto-start" 2>&1
  # Counting from confirmed startup conservatively waits beyond trace duration.
  trace_ready=$(uptime_seconds)
  trace_wait_until=$((trace_ready + duration + 1))
  read up rest < /proc/uptime
  echo "$up" > "$prefix.trace-ready-uptime"
}
finish_trace() {
  now=$(uptime_seconds)
  remaining=$((trace_wait_until - now))
  if [ "$remaining" -gt 0 ]; then sleep "$remaining"; fi
  read up rest < /proc/uptime
  echo "$up" > "$prefix.trace-collected-uptime"
  test -s "$trace"
  cp "$trace" "$prefix.perfetto-trace"
  pidof "$pkg" > "$prefix.pid-after"
  cmp -s "$out/process.pid" "$prefix.pid-after"
  snapshot "$prefix.after"
  dumpsys gfxinfo "$pkg" framestats > "$prefix.gfxinfo"
  date +%s > "$prefix.complete"
}

# Fresh-process first appearance, with recording active before app launch.
am force-stop "$pkg"
if pidof "$pkg" > "$out/pid-stopped"; then
  echo 'App still running after force-stop' >&2; exit 3
fi
start_trace cold 6 cold
am start -W -n "$activity" --es seaglass.scene long-list --ez seaglass.profile true > "$prefix.startup"
grep -q '^Status: ok' "$prefix.startup"
grep -q '^LaunchState: COLD' "$prefix.startup"
pidof "$pkg" > "$out/process.pid"
cp "$out/process.pid" "$prefix.pid-before"
finish_trace
dumpsys meminfo -a "$pkg" > "$out/$label-cold.meminfo"

for phase in down revisit idle; do
  prefix="$out/$label-$phase"
  pidof "$pkg" > "$prefix.pid-before"
  cmp -s "$out/process.pid" "$prefix.pid-before"
  snapshot "$prefix.before"
  dumpsys gfxinfo "$pkg" reset > "$prefix.gfx-reset"
  if [ "$phase" = idle ]; then
    start_trace "$phase" 4 idle
  else
    start_trace "$phase" 8 scroll
    sh "$root/gesture.sh" "$config" "$phase" "$prefix.gestures"
  fi
  finish_trace
done
dumpsys meminfo -a "$pkg" > "$out/$label-idle.meminfo"
read block_end rest < /proc/uptime
echo "$block_end" > "$out/block.complete-uptime"
date +%s > "$out/block.complete"
echo "SEAGLASS_SCROLL_BLOCK_COMPLETE $label"
