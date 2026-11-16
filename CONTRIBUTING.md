# Contributing to Argon

Thanks for taking the time. Argon is a personal project built around one TV, so the most useful contributions are
the ones that make it easier to run on another TV, safer to debloat with, or better documented. Bug reports from other
Google TV models are especially welcome.

Please read the [Code of Conduct](https://github.com/abhinav-adtechs/.github/blob/main/CODE_OF_CONDUCT.md) before
taking part. To report a security problem (for example in the web remote's sign-in), follow the
[security policy](https://github.com/abhinav-adtechs/.github/blob/main/SECURITY.md) instead of opening a public issue.

## Development setup

| You need                     | Version / notes                                                                                      |
| ---------------------------- | ---------------------------------------------------------------------------------------------------- |
| JDK                          | 17 (`tools/build_install.sh` looks for `JAVA_HOME_17`, then `/usr/libexec/java_home -v 17` on macOS) |
| Android SDK                  | `platforms;android-34` and `build-tools;34.0.0`, in `~/Library/Android/sdk` or `ANDROID_HOME`        |
| Gradle                       | Comes from the wrapper (`launcher/gradlew`, Gradle 8.11.1, Android Gradle Plugin 8.7.3)              |
| `adb`                        | Any recent platform-tools                                                                            |
| A Google TV                  | With **USB debugging** and **Network debugging** on (see [Quick start](README.md#quick-start))       |
| Node.js (optional)           | To run the same syntax checks as CI                                                                  |
| Python 3 + Pillow (optional) | Only for `tools/make_symbols.py`                                                                     |

```bash
git clone https://github.com/abhinav-adtechs/argon.git && cd argon
adb connect <tv-ip>:5555
TV=<tv-ip>:5555 tools/build_install.sh
```

Optional: put a TMDB key in `launcher/secrets.properties` (gitignored) for the picks and the screensaver photos; see
[The TMDB key](launcher/README.md#the-tmdb-key).

### Where to make a change

| You want to change…                   | Edit                                                       | Then                                  |
| ------------------------------------- | ---------------------------------------------------------- | ------------------------------------- |
| The home screen, screensaver or stats | `launcher/app/src/main/java/com/argon/launcher/`           | `tools/build_install.sh`              |
| The web remote's page                 | `remote/public/` (it ships inside the APK)                 | `tools/build_install.sh`              |
| The web remote's server or API        | `launcher/app/src/main/java/com/argon/launcher/remote/`    | `tools/build_install.sh`              |
| Lights and plugs                      | `launcher/app/src/main/java/com/argon/launcher/smarthome/` | `tools/build_install.sh`              |
| Rebuild-time defaults                 | `launcher/app/src/main/res/values/config.xml`              | `tools/build_install.sh`              |
| What gets disabled or restored        | `tools/disable.sh`, `tools/restore.sh`                     | Test the restore path too (see below) |

Useful while you work:

```bash
adb logcat -s Remote        # the web remote's server
adb logcat -s SmartHome     # lights and plugs
adb logcat -s Notice        # notices over any app
tools/shot.sh name DPAD_DOWN DPAD_RIGHT   # press keys, then save shots/name.png
tools/notice.sh -t "Argon" "Installed"    # put a card on the TV
```

### Keeping your data while you test

`tools/build_install.sh` installs with `install -r`, which keeps the launcher's data (viewing stats, the remote's
password, the lights and plugs). An uninstall does not, and release builds are signed with your machine's debug key,
so replacing a build made on another machine needs an uninstall first. Before anything risky, keep a copy:

```bash
adb exec-out content read --uri content://com.argon.launcher.files/watch.db > watch.db
adb exec-out content read --uri content://com.argon.launcher.files/smarthome.json > smarthome.json
```

## Checks

CI runs syntax checks on every push and pull request. Run the same thing locally before you open a PR:

```bash
for f in remote/public/*.js; do node --check "$f"; done
node -e "JSON.parse(require('fs').readFileSync('remote/public/manifest.webmanifest', 'utf8'))"
for f in tools/*.sh; do bash -n "$f"; done
python3 -c 'import ast, sys; ast.parse(open(sys.argv[1]).read(), sys.argv[1])' tools/make_symbols.py
```

CI does not build the APK yet, so also build it yourself (`cd launcher && ./gradlew assembleRelease`, or
`tools/build_install.sh`) and say in the PR which TV you tried it on.

## Conventions

- **Java, no libraries.** The launcher uses only the Android SDK and the JDK. A new dependency needs a strong reason
  in the PR.
- **Shell only where the shell is needed.** Anything that needs the shell user goes through the web remote's adb link
  or a `DUMP`-protected receiver or provider, never an exported component other apps could call.
- **Everything reversible.** A change to `tools/disable.sh` needs a matching path in `tools/restore.sh`, and must keep
  logging to `backup/disabled_by_tools.txt`.
- **Comments say why.** Follow the existing style: a short comment at the top of each class saying what it is for and
  what it costs.
- **Docs travel with the code.** User-visible behaviour is documented in `launcher/README.md` or `remote/README.md`;
  update the right one in the same PR.
- **Never commit** `launcher/secrets.properties`, device snapshots (anything in `backup/` except
  `disabled_by_tools.txt`), `shots/`, or exports of `watch.db` and `smarthome.json` (they hold keys).

## Branches and commits

- Branch from `main`: `feat/<short-name>`, `fix/<short-name>`, `docs/<short-name>`.
- Use [Conventional Commits](https://www.conventionalcommits.org/) with a scope where it helps:
  `feat(remote): …`, `fix(launcher): …`, `docs(tools): …`, `chore(ci): …`.
- Subject in the imperative, under 72 characters. The body says **why**: what was wrong on the TV, what you measured,
  what you tried that didn't work. The existing history is a good model.

## Filing issues

- **Bugs:** the TV model and Android version, what you did, what you expected, what happened, and the relevant
  `adb logcat -s Remote` / `SmartHome` / `Notice` lines. Screenshots from `tools/shot.sh` help.
- **Another TV model:** `adb shell getprop ro.product.model`, `adb shell getprop ro.build.version.release`, and which
  packages from the [disabled list](README.md#what-was-disabled-44-packages) exist on it.
- **Ideas:** what you want to do from the couch, and why the current screen doesn't do it.

## Pull request checklist

- [ ] The checks above pass locally
- [ ] The APK builds, and you've installed it on a TV (say which model)
- [ ] Anything new that `tools/disable.sh` does can be undone by `tools/restore.sh`
- [ ] No new third-party libraries in the launcher
- [ ] `launcher/README.md` or `remote/README.md` is updated for user-visible changes
- [ ] `CHANGELOG.md` has a line under **Unreleased**
- [ ] No keys, tokens, device snapshots or personal data in the diff
