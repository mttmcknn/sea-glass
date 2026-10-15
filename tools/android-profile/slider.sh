#!/system/bin/sh
set -eu
label="$1"
y="$2"
# Reset recipe to Reference/Mix without changing the current scroll positions.
input tap 1492 226
sleep 1
# Same two-way drag on each build, with all seven glass components visible.
dumpsys gfxinfo dev.mttmcknn.seaglass.sample reset >/dev/null
perfetto --background-wait --txt -c /data/misc/perfetto-configs/seaglass.pbtxt -o /data/misc/perfetto-traces/seaglass-${label}.perfetto-trace
input swipe 1100 "$y" 1500 "$y" 600
sleep 0.25
input swipe 1500 "$y" 1100 "$y" 600
sleep 1
dumpsys gfxinfo dev.mttmcknn.seaglass.sample framestats > /data/local/tmp/seaglass-profile/${label}.gfxinfo
sleep 5
