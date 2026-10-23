#!/usr/bin/env bash
# Usage: tools/disable.sh pkg [pkg...]  — disables for user 0, logs to backup/disabled_by_tools.txt
source "$(dirname "$0")/env.sh"
for p in "$@"; do
  # The system starts persistent apps at boot even when they're disabled, so those are uninstalled
  # for user 0 instead (the APK stays in /system; tools/restore.sh brings them back).
  if tv dumpsys package "$p" 2>/dev/null | grep -q "pkgFlags=.* PERSISTENT"; then
    out=$(tv pm uninstall -k --user 0 "$p" 2>&1)
    ok="Success"
  else
    out=$(tv pm disable-user --user 0 "$p" 2>&1)
    ok="new state: disabled-user"
  fi
  echo "$p: $out"
  if echo "$out" | grep -q "$ok"; then
    grep -qx "$p" "$LOG" 2>/dev/null || echo "$p" >> "$LOG"
  fi
done
