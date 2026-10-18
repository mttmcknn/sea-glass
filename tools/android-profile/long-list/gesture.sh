#!/system/bin/sh
# Arguments: inspected generated config, down|revisit, output gesture log.
set -eu
. "$1"
direction="$2"
log="$3"
case "$direction" in
  down) from_y="$SWIPE_LOW_Y"; to_y="$SWIPE_HIGH_Y";;
  revisit) from_y="$SWIPE_HIGH_Y"; to_y="$SWIPE_LOW_Y";;
  *) exit 2;;
esac
stamp() { read up rest < /proc/uptime; echo "$up $*" >> "$log"; }
i=1
while [ "$i" -le "$DRAG_COUNT" ]; do
  stamp "drag-start $i $SWIPE_X $from_y $to_y $DRAG_DURATION_MS"
  input swipe "$SWIPE_X" "$from_y" "$SWIPE_X" "$to_y" "$DRAG_DURATION_MS"
  stamp "drag-end $i"
  sleep "$DRAG_GAP_SECONDS"
  i=$((i + 1))
done
i=1
while [ "$i" -le "$FLING_COUNT" ]; do
  stamp "fling-start $i $SWIPE_X $from_y $to_y $FLING_DURATION_MS"
  input swipe "$SWIPE_X" "$from_y" "$SWIPE_X" "$to_y" "$FLING_DURATION_MS"
  stamp "fling-end $i"
  sleep "$FLING_GAP_SECONDS"
  i=$((i + 1))
done
