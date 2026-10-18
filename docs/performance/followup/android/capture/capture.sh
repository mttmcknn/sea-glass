#!/system/bin/sh
# Same reset, drag, endpoint and settling intervals as gc-followup/capture.sh.
set -eu
label="$1"
mode="$2"
out="$3"
case "$label" in *[!a-zA-Z0-9-]*|'') echo 'Invalid capture label' >&2; exit 2;; esac
case "$mode" in tumble|idle|count) ;; *) echo 'Invalid mode' >&2; exit 2;; esac
test -d "$out"
test ! -e "$out/$label.started"
pkg=dev.mttmcknn.seaglass.sample
activity=dev.mttmcknn.seaglass.sample/dev.mttmcknn.seaglass.android.MainActivity
trace=/data/misc/perfetto-traces/seaglass-${label}.perfetto-trace
date +%s > "$out/$label.started"
pidof "$pkg" > "$out/$label.pid-before"
counting=false
cleanup() {
  if [ "$counting" = true ]; then
    dumpsys activity "$activity" sg-stop-counting > "$out/$label.count-stop"
  fi
}
trap cleanup EXIT HUP INT TERM
# Coordinates are valid only for the inspected 1600x2400 / 320 dpi Components screen.
input tap 1492 226
sleep 1
dumpsys gfxinfo "$pkg" reset >/dev/null
if [ "$mode" = count ]; then
  counting=true
  dumpsys activity "$activity" sg-start-counting > "$out/$label.count-start"
fi
dumpsys activity "$activity" sg-runtime-stats > "$out/$label.before"
grep -q 'SEAGLASS_RUNTIME_STATS' "$out/$label.before"
grep -q "\"allocationCountingRequested\":$counting" "$out/$label.before"
perfetto --background-wait --txt -c /data/misc/perfetto-configs/seaglass-final.pbtxt -o "$trace"
if [ "$mode" = idle ]; then
  sleep 2
else
  input swipe 1100 1755 1500 1755 600
  sleep 0.25
  input swipe 1500 1755 1100 1755 600
fi
sleep 1
dumpsys activity "$activity" sg-runtime-stats > "$out/$label.after"
dumpsys gfxinfo "$pkg" framestats > "$out/$label.gfxinfo"
sleep 5
dumpsys meminfo -a "$pkg" > "$out/$label.meminfo"
if [ "$mode" = count ]; then
  dumpsys activity "$activity" sg-stop-counting > "$out/$label.count-stop"
  counting=false
fi
grep -q 'SEAGLASS_RUNTIME_STATS' "$out/$label.after"
pidof "$pkg" > "$out/$label.pid-after"
cmp -s "$out/$label.pid-before" "$out/$label.pid-after"
test -s "$trace"
cp "$trace" "$out/$label.perfetto-trace"
date +%s > "$out/$label.complete"
