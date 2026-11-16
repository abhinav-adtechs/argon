# Troubleshooting

Symptoms and fixes, collected from the [root README](../README.md), [launcher/README.md](../launcher/README.md) and
[remote/README.md](../remote/README.md). Commands assume `adb` is connected to the TV (`adb connect <tv-ip>:5555`);
add `-s <tv-ip>:5555` if more than one device is attached.

- [Install and home screen](#install-and-home-screen)
- [adb](#adb)
- [The web remote](#the-web-remote)
- [Standby and waking](#standby-and-waking)
- [Notices](#notices)
- [Picks, stats, lights and speakers](#picks-stats-lights-and-speakers)
- [Undoing things](#undoing-things)

## Install and home screen

**Home stays black right after `tools/build_install.sh`.**
The system couldn't find the contiguous memory (CMA) for the launcher's window
(`GraphicBufferAllocator: Failed to allocate (1920 x 1080)` in logcat). The script already restarts the launcher once
for this. Apps left running in the background (TV Settings, a streaming app) hold a lot of it; check with
`adb shell grep CmaFree /proc/meminfo`, then restart home:

```bash
adb shell am force-stop com.argon.launcher
adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME
```

**The stock Google TV home keeps coming back.**
Google TV picks HOME by intent-filter priority before the user's default, so `cmd package set-home-activity` alone is
ignored while `com.google.android.apps.tv.launcherx` or `com.google.android.tungsten.setupwraith` is enabled.
`tools/build_install.sh` disables both; see [Why setupwraith is disabled](../README.md#why-setupwraith-is-disabled).

**The build fails before it starts.**
`tools/build_install.sh` needs JDK 17 (from `JAVA_HOME_17`, or `/usr/libexec/java_home -v 17` on macOS) and the
Android SDK with `platforms;android-34` and `build-tools;34.0.0` in `~/Library/Android/sdk` or `ANDROID_HOME`.

**`No launcher/secrets.properties: building without the TMDB picks`.**
Not an error. Without a TMDB key everything works except the picks and the screensaver photos. See
[The TMDB key](../launcher/README.md#the-tmdb-key).

**The Home screen tab in the web remote does nothing.**
It talks to the launcher's `ControlReceiver`, so the launcher must be the build from this repo
(`tools/build_install.sh`).

## adb

**`adb connect` fails with "No route to host" on macOS while the port is open.**
macOS Local Network privacy is blocking adb. If the adb daemon was started from inside another app (it happened when
it was started from the Claude app), run `adb kill-server` and start it again from Terminal.app. Otherwise use the
Python `adb-shell` package as the client.

**The TV asks "Allow USB debugging?" twice.**
Expected: once for your computer, and once when the launcher's web remote connects to the TV's own adbd with its own
key. Tick **Always allow from this computer** both times. If something covers the prompt (the home screen right after an
install does), a new one appears within a minute.

## The web remote

**`http://argon.local:7777` doesn't open.**
Try `http://<tv-ip>:7777`. `argon.local` comes from the remote's own multicast DNS responder, which retries every 5 s
if Wi-Fi came up after it, so give it a moment after a boot. Logs: `adb logcat -s Remote`.

**A red banner across the top of the page.**
The page can't reach the TV, or the remote can't reach the TV's adbd. It clears by itself once the connection is back.
On the TV, the Inputs tab's **Web remote** tile shows which part is down (server, TV link, keys, `argon.local`);
**Restart** there kills the `:remote` process and starts it again in a few seconds.

**Forgot the password.**
**Forgot the password?** on the sign-in page asks for a new 6-digit code shown on the TV, then a new password. It signs
every device out.

**Sign-in is refused after a few wrong passwords.**
Five wrong passwords or codes from one address lock it out for 30 s, doubling on each further miss up to 15 minutes.
Wait it out.

**The TV picture says it's hidden.**
Netflix and other protected video refuse screen capture while they play. That's the TV, not the remote.

**The type bar doesn't open when a text box is focused on the TV.**
It opens when the TV's own on-screen keyboard comes up. Apps that draw their own keyboard don't trigger it; press `/`
or use the keyboard button.

## Standby and waking

**The web remote stops answering a few seconds after the TV goes to sleep.**
Android's Low Power Standby cuts every app off the network. The server turns it off through its adb shell when it
starts and whenever an Energy mode turns it back on. To check, find the launcher's UID
(`adb shell pm list packages -U com.argon.launcher`), then:

```bash
adb shell dumpsys netpolicy | grep UID=<launcher uid>
```

`effective=NONE` is reachable; `effective=LOW_POWER_STANDBY` is cut off.

**Standby turns the TV (and the remote) fully off.**
Out of the box it does. Turn on **Settings › System › Power & Energy › Power › Screenless mode** and pick the
**Optimized** Energy mode; see [Waking the TV from a laptop](../README.md#waking-the-tv-from-a-laptop).

**`tools/wake.sh` gives up after 90 s.**
The TV is fully off: Screenless mode is off, or it has been asleep for more than 3 hours. Only the remote or the TV's
own button turns it on. (Whether it can be woken after the 3 hours is not yet tested.)

## Notices

**Notices stopped appearing.**
Drawing over other apps needs the overlay permission, which Android TV has no settings screen for. Grant it again:

```bash
adb shell appops set com.argon.launcher SYSTEM_ALERT_WINDOW allow
```

`adb logcat -s Notice` says when it's missing. Nothing shows while the TV is in standby; wake it first with
`adb shell input keyevent KEYCODE_WAKEUP`.

**`am broadcast` for a notice does nothing from an app.**
By design: the receiver requires the `DUMP` permission, which only adb (the shell user) and root hold.

## Picks, stats, lights and speakers

**No For You, show or movie widgets, and no screensaver photos.**
There's no TMDB key in the build. Add one to `launcher/secrets.properties` and run `tools/build_install.sh` again.

**Netflix time shows as "Other on Netflix".**
Netflix doesn't name what's playing, so its time is split between the titles it updates in Continue watching. Time with
no update nearby stays as _Other_. See [Viewing stats](../launcher/README.md#viewing-stats).

**A Tuya plug stops responding.**
A plug takes only a few local connections; a phone with the Smart Life app open can briefly take the slot. The TV
retries (1 s, doubling to 30 s). A plug that was reset and added again has a new key: use **Fetch keys again** in the
remote's Home screen tab. Logs: `adb logcat -s SmartHome`.

**The Hue Bridge won't pair.**
Press the round button on top of the bridge within a minute of pressing **Pair** (the TV shows a notice).

**A Bluetooth speaker won't connect from the TV.**
Connecting runs as the shell over the web remote's adb link, so the web remote has to be running (check its tile on the
Inputs tab). A speaker that's off or out of range says so in a message once Bluetooth gives up on it.

## Undoing things

**Re-enable one app:** `tools/restore.sh <package>`. **Everything, and the stock home:** `tools/restore.sh`, then
follow the two lines it prints (uninstall `com.argon.launcher`, turn Low Power Standby back on).

**Before a firmware update:** re-enable the update packages, update, then disable them again:

```bash
tools/restore.sh com.xiaomi.mitv.updateservice com.mediatek.tv.systemupdater com.mitv.upgrader
# update from Settings, then:
tools/disable.sh com.xiaomi.mitv.updateservice com.mediatek.tv.systemupdater com.mitv.upgrader
```

**Lost the viewing stats or the lights and plugs after a reinstall.**
If you kept a copy, put it back before the launcher opens the file (force-stop it first):

```bash
adb shell am force-stop com.argon.launcher
adb shell content write --uri content://com.argon.launcher.files/watch.db < watch.db
adb shell content write --uri content://com.argon.launcher.files/smarthome.json < smarthome.json
```
