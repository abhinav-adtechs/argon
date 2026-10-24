#!/usr/bin/env bash
# Usage: tools/wake.sh [seconds]  — turns the TV on from standby (needs Screenless mode, see "Waking the TV" in README)
source "$(dirname "$0")/env.sh"
# Asleep in Screenless mode, the TV drops off the Wi-Fi for seconds at a time, so retry until it reports Awake.
end=$((SECONDS + ${1:-90}))
while [ $SECONDS -lt $end ]; do
  adb connect "$TV" >/dev/null 2>&1
  if tv input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 &&
    tv dumpsys power 2>/dev/null | grep -q "mWakefulness=Awake"; then
    echo "TV is on"
    exit 0
  fi
  sleep 2
done
echo "TV didn't answer in ${1:-90} s: it's fully off (Screenless mode off, or more than 3 hours asleep)" >&2
exit 1
