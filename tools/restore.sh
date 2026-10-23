#!/usr/bin/env bash
# Re-enables packages disabled by tools/disable.sh.
# Usage: tools/restore.sh            — re-enable everything and bring back the stock Google TV home
#        tools/restore.sh pkg [...]  — re-enable only the given packages
source "$(dirname "$0")/env.sh"
if [ $# -gt 0 ]; then pkgs="$*"; else pkgs=$(cat "$LOG" 2>/dev/null); fi
for p in $pkgs; do
  # Persistent apps were uninstalled for user 0 rather than disabled (see tools/disable.sh).
  tv pm list packages "$p" | tr -d '\r' | grep -qx "package:$p" || tv cmd package install-existing --user 0 "$p"
  out=$(tv pm enable --user 0 "$p" 2>&1)
  echo "$p: $out"
  if echo "$out" | grep -q "new state: enabled"; then
    grep -vx "$p" "$LOG" > "$LOG.tmp" 2>/dev/null; mv "$LOG.tmp" "$LOG"
  fi
done
if [ $# -eq 0 ]; then
  tv cmd package set-home-activity --user 0 com.google.android.apps.tv.launcherx/.home.HomeActivity
  tv settings delete secure screensaver_components
  tv am start -a android.intent.action.MAIN -c android.intent.category.HOME
  echo "Stock launcher restored. To remove the custom one: adb -s $TV uninstall com.argon.launcher"
fi
