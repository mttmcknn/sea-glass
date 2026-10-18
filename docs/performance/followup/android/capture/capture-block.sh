#!/system/bin/sh
# Install, speed AOT and process launch are performed through the plugin before this.
set -eu
variant="$1"
round="$2"
case "$variant" in baseline|checkpoint|performance) ;; *) exit 2;; esac
case "$round" in 1|2) ;; *) exit 2;; esac
root=/data/local/tmp/seaglass-final
out="$root/$variant-block-$round"
# Never overwrite a complete or partially recorded block after uncertain results.
test ! -e "$out"
mkdir "$out"
date +%s > "$out/block.started"
printf 'variant=%s\nround=%s\n' "$variant" "$round" > "$out/block.txt"
pidof dev.mttmcknn.seaglass.sample > "$out/process.pid"
activity=dev.mttmcknn.seaglass.sample/dev.mttmcknn.seaglass.android.MainActivity
# Warm the diagnostic dump path before any workload and verify counting is off.
dumpsys activity "$activity" sg-runtime-stats > "$out/diagnostic-warmup.txt"
grep -q '"allocationCountingRequested":false' "$out/diagnostic-warmup.txt"
sh "$root/capture.sh" "$variant-warmup-$round" tumble "$out"
first=$((round * 2 - 1))
second=$((round * 2))
sh "$root/capture.sh" "$variant-tumble-$first" tumble "$out"
sh "$root/capture.sh" "$variant-tumble-$second" tumble "$out"
if [ "$round" = 1 ]; then
  # Separate diagnostics follow normal timing; legacy counting runs last.
  sh "$root/capture.sh" "$variant-idle-1" idle "$out"
  sh "$root/capture.sh" "$variant-count-1" count "$out"
fi
date +%s > "$out/block.complete"
echo "SEAGLASS_BLOCK_COMPLETE $variant $round"
