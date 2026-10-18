#!/system/bin/sh
# Separate visual run, never part of timed blocks. App already freshly launched
# with --es seaglass.scene long-list --ez seaglass.profile true;
# recording, if desired, is owned by root.
set -eu
label="$1"
config="$2"
case "$label" in *[!a-zA-Z0-9-]*|'') exit 2;; esac
. "$config"
root=/data/local/tmp/seaglass-scroll
out="$root/visual/$label"
activity=dev.mttmcknn.seaglass.sample/dev.mttmcknn.seaglass.android.MainActivity
test ! -e "$out"
mkdir -p "$out"
cp "$config" "$out/device-config.sh"
date +%s > "$out/visual.started"
for phase in top down revisit; do
  if [ "$phase" != top ]; then
    sh "$root/gesture.sh" "$config" "$phase" "$out/$phase.gestures"
  fi
  sleep 3
  dumpsys activity "$activity" sg-runtime-stats > "$out/$phase.before"
  grep -Fq "$DATASET_ID" "$out/$phase.before"
  screencap -p "$out/$phase.png"
  dumpsys activity "$activity" sg-runtime-stats > "$out/$phase.after"
done
date +%s > "$out/visual.complete"
echo "SEAGLASS_SCROLL_VISUAL_COMPLETE $label"
