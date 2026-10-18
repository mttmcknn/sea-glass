#!/system/bin/sh
# Separate fresh-process startup protocol; never combine with warm gesture medians.
set -eu
variant="$1"
run="$2"
case "$variant" in baseline|performance) ;; *) exit 2;; esac
case "$run" in 1|2|3) ;; *) exit 2;; esac
label="$variant-cold-$run"
root=/data/local/tmp/seaglass-final
out="$root/cold/$label"
pkg=dev.mttmcknn.seaglass.sample
activity="$pkg/dev.mttmcknn.seaglass.android.MainActivity"
trace="/data/misc/perfetto-traces/seaglass-${label}.perfetto-trace"
# Refuse to overwrite uncertain or completed captures.
test ! -e "$out"
mkdir -p "$out"
date +%s > "$out/$label.started"
am force-stop "$pkg"
if pidof "$pkg" > "$out/$label.pid-stopped"; then
  echo 'App process still present after force-stop' >&2
  exit 3
fi
# Recording is active before the launch. The installed APK has already had speed AOT.
perfetto --background-wait --txt -c /data/misc/perfetto-configs/seaglass-final.pbtxt -o "$trace"
am start -W -n "$activity" > "$out/$label.startup"
grep -q '^Status: ok' "$out/$label.startup"
grep -q '^LaunchState: COLD' "$out/$label.startup"
pidof "$pkg" > "$out/$label.pid-before"
# Match evidence/extra.sh's original cold settle interval; trace duration is six seconds.
sleep 6
dumpsys gfxinfo "$pkg" framestats > "$out/$label.gfxinfo"
dumpsys meminfo "$pkg" > "$out/$label.meminfo"
dumpsys activity "$activity" sg-runtime-stats > "$out/$label.runtime"
grep -q '"allocationCountingRequested":false' "$out/$label.runtime"
pidof "$pkg" > "$out/$label.pid-after"
cmp -s "$out/$label.pid-before" "$out/$label.pid-after"
test -s "$trace"
cp "$trace" "$out/seaglass-${label}.perfetto-trace"
date +%s > "$out/$label.complete"
echo "SEAGLASS_COLD_COMPLETE $variant $run"
