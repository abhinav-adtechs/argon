# Changelog

All notable changes to Argon are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses [Semantic Versioning](https://semver.org/).
The launcher's own `versionName` is set in `launcher/app/build.gradle`.

## [Unreleased]

### Added

- MIT `LICENSE`, `CONTRIBUTING.md`, this changelog, `docs/ARCHITECTURE.md` and `docs/TROUBLESHOOTING.md`.
- A rewritten README with a banner, feature overview, architecture diagram and configuration reference.
- CI: syntax checks for the web remote's scripts and manifest, the shell tools and `tools/make_symbols.py`.

## [2.1.0] - 2026-11-14

### Added

- **Football Central**, a fourth tab with the club's season from ESPN's public API (Manchester City by default): a
  match centre that follows the game (preview, lineups on a pitch an hour before kick-off, live score, goals, cards
  and stats), news with an on-TV reader, City items from the transfer column, form, fixtures, the league and
  Champions League tables, and the cups ([#2](https://github.com/abhinav-adtechs/argon/pull/2)).
- A City panel on the screensaver: the live or next match, the place in all four competitions, form and a headline.
- **Speakers** on the Inputs tab: the TV's own and the paired Bluetooth ones, which one has the sound, and Connect /
  Disconnect / Use TV speakers. Connecting runs `BluetoothTool` as the shell over the web remote's adb link.
- **Web remote** health on the Inputs tab: whether the server, its adb link, the keys and `argon.local` are up
  (`GET /api/health`, answered only on the TV's loopback), and a Restart.
- `tools/build_install.sh` grants `BLUETOOTH_CONNECT`.

### Changed

- Tiles with a symbol (inputs, speakers, the room, lights and plugs) show their name once, beside the tile when
  focused, like an app's tile.
- `City.java` replaces `Football.java` as the one football data source, so the LIVE pill and the match widget read the
  same data. Crests are fetched on the TV, not kept in the repo.
- The tools default to the TV's new address, `192.168.2.14:5555`.

### Fixed

- The web remote stays on the network while the TV sleeps: Android's Low Power Standby cut every app off the network,
  so the server turns it off through its adb shell once adb is up, and again whenever picking an Energy mode turns it
  back on. `tools/restore.sh` says how to turn it back on.

### Removed

- The home-screen music: the player, both loops, the `ambient_volume` setting, the web remote's slider and the
  loop-cutting tool ([#1](https://github.com/abhinav-adtechs/argon/pull/1)).

## [2.0.0] - 2026-10-25

First public release of the launcher (`versionName` 2.0), the web remote and the tools.

### Added

- The launcher, `com.argon.launcher`: a PS5-style home screen with the Welcome hub, app hubs, the Apps, Inputs and
  Home tabs, All Apps, the match widget (ESPN), the screensaver, viewing stats, notices over any app, and the room's Hue lights and Tuya plugs.
- The web remote, served by the TV on port 7777 and at `argon.local`, with phone, tablet, couch and laptop layouts and
  the Home screen, Settings, Files, Watching, Device and Admin tabs.
- The tools: build and install, disable and restore, notices, screenshots and the icon generator; the list of
  disabled packages in `backup/disabled_by_tools.txt`.
- `tools/wake.sh`, which turns the TV on from standby over adb, retrying while it naps (needs Screenless mode).
- The web remote holds a power press while it can't reach the TV and sends it as one tap once the TV is back; its CSS
  and scripts are linked with `?v=<install>` and cached, so opening it mid-nap needs one request.
- Docs: a root overview, `launcher/README.md` and `remote/README.md`, each with screenshots and video.

### Changed

- The home screen's ambient loops changed from nature sounds to elevator music (removed again in 2.1.0).

### Fixed

- `argon.local` keeps answering when Wi-Fi comes up after the remote service starts: the mDNS thread opens its own
  socket and, on any error, closes it, waits 5 s and tries again.

[Unreleased]: https://github.com/abhinav-adtechs/argon/compare/v2.1.0...HEAD
[2.1.0]: https://github.com/abhinav-adtechs/argon/compare/v2.0.0...v2.1.0
[2.0.0]: https://github.com/abhinav-adtechs/argon/commits/v2.0.0
