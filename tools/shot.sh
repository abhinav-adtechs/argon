#!/usr/bin/env bash
# Usage: tools/shot.sh name [key ...]  — presses keys (e.g. DPAD_DOWN) then saves shots/name.png
source "$(dirname "$0")/env.sh"
name=$1; shift
for k in "$@"; do tv input keyevent "KEYCODE_$k"; sleep 0.5; done
sleep 1
mkdir -p "$ROOT/shots"
adb -s "$TV" exec-out screencap -p > "$ROOT/shots/$name.png"
echo "$ROOT/shots/$name.png"
