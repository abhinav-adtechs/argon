#!/usr/bin/env bash
# Builds the launcher, installs it on the TV, makes it the default home and disables the stock launcher.
set -euo pipefail
source "$(dirname "$0")/env.sh"
export JAVA_HOME="${JAVA_HOME_17:-$(/usr/libexec/java_home -v 17)}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
PKG=com.argon.launcher

cd "$ROOT/launcher"
echo "sdk.dir=$ANDROID_HOME" > local.properties
[ -f secrets.properties ] || echo "No launcher/secrets.properties: building without the TMDB picks (see the README)."
# Release build: ART won't use compiled code for a debuggable app, so a debug build starts about twice as slow.
./gradlew -q assembleRelease
adb -s "$TV" install -r app/build/outputs/apk/release/app-release.apk
# A fresh install runs from the APK until the nightly dexopt; compile it now.
tv cmd package compile -m speed -f "$PKG"
tv pm grant "$PKG" android.permission.READ_TV_LISTINGS
tv appops set "$PKG" SYSTEM_ALERT_WINDOW allow
# Viewing stats: usage events for app and screen time, media sessions for what's playing.
tv appops set "$PKG" GET_USAGE_STATS allow
tv cmd notification allow_listener "$PKG/$PKG.MediaListener"
tv cmd package set-home-activity --user 0 "$PKG/.HomeActivity"
# The screensaver: the TMDB picks' photos with a glass clock (tools/restore.sh puts the stock one back).
tv settings put secure screensaver_components "$PKG/.Screensaver"
# Google TV resolves HOME by intent-filter priority before the user's choice: the stock launcher (2)
# and setupwraith's RecoveryActivity (1) both outrank any third-party launcher (0), so both go.
disabled=$(tv pm list packages -d)
for p in com.google.android.apps.tv.launcherx com.google.android.tungsten.setupwraith; do
  echo "$disabled" | grep -qx "package:$p" || "$ROOT/tools/disable.sh" "$p"
done
# Restarted once more: right after an install the system often can't find the contiguous memory for the
# launcher's window ("GraphicBufferAllocator: Failed to allocate (1920 x 1080)") and home stays black.
tv am force-stop "$PKG"
sleep 1
tv am start -a android.intent.action.MAIN -c android.intent.category.HOME
