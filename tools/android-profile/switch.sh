#!/system/bin/sh
set -eu
label="$1"
input tap 1492 226
sleep 1
dumpsys gfxinfo dev.mttmcknn.seaglass.sample reset >/dev/null
perfetto --background-wait --txt -c /data/misc/perfetto-configs/seaglass-switch.pbtxt -o /data/misc/perfetto-traces/seaglass-${label}.perfetto-trace
# Shape study, Components, Music player, Components; same visible selector.
for y in 897 801 1089 801; do
  input tap 411 689
  sleep 0.3
  input tap 144 "$y"
  sleep 0.8
done
sleep 1
dumpsys gfxinfo dev.mttmcknn.seaglass.sample framestats > /data/local/tmp/seaglass-profile/${label}.gfxinfo
sleep 6
