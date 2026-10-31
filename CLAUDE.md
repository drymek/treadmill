# CLAUDE.md

Guidance for Claude Code (and other AI agents) working in this repository.

## Project

Open-source control of `RZ_TreadMil` Bluetooth LE treadmills (GATT service `FFF0`): a reverse-engineered
protocol specification (`docs/protocol.md`) and implementations in several languages. Libraries go to
`src/<language>/lib`, applications to `src/<language>/<app>`.

- Everything in the repository is written in English: code, comments, docs, commit messages.
- Namespace for module paths and identifiers: `craftersy.com` (Go module `craftersy.com/treadmill/src/go/lib`).
- Every command lives in the root `Taskfile.yml` (`task --list`). Run `task go:check` before committing, and
  `task kotlin:check` when `src/kotlin` changed (`task kotlin:setup` installs JDK 21 and the Android SDK).
- `main` is protected: every change goes through a pull request with green CI. Each push to `main` publishes a
  `panel-v0.1.<run>` release with the Go panel binaries and the signed APK (see the release jobs in
  `.github/workflows/ci.yml`). The APK signing key lives in repository secrets; never commit a keystore.
- `docs/protocol.md` is the source of truth. A protocol change needs evidence (log lines, photos) and a test
  with a captured frame.

## Layout

| Path | What |
|---|---|
| `src/go/lib` | Go library: `protocol` (pure framing), `treadmill` (client, acknowledged commands, `DialReconnecting`), `bluetooth` (BLE transport), `treadmilltest` (simulator), `e2e` (hardware tests, `e2e` build tag) |
| `src/go/panel` | terminal app (Bubble Tea v2, Lip Gloss v2), a virtual HMS console; separate module, `-sim` for the simulator |
| `src/go/tools/probe` | research tool, separate module; logs every notification and sends any command |
| `src/go/tools/workout` | validates `.workout.yaml` files and prints their plans (`task workout -- <file>`); parser in `lib` (package `workout`) |
| `src/kotlin/lib` | Kotlin/JVM library (package `com.craftersy.treadmill`): `protocol`, `client` (acknowledged commands, `ReconnectingTransport`), `simulator`; no Android dependency |
| `src/kotlin/panel` | Android app (Jetpack Compose, minSdk 31), the virtual HMS console; BLE transport in `panel/bluetooth`. One Gradle build in `src/kotlin` |
| `docs/workout.md` | `.workout.yaml` workout file format (planned workouts for the panels) |
| `docs/measurements` | raw session log and photos backing the specification |

Record user-visible library changes in `src/go/lib/CHANGELOG.md` and `src/kotlin/lib/CHANGELOG.md` under
**Unreleased**. Keep the Go and Kotlin panels feature-equal.

## Safety

The treadmill is real machinery. Send commands that move the belt only after the user confirms, one at a time,
with the safety key attached. Prefer the simulator for anything that does not need hardware. The motion
end-to-end test (`task --yes go:e2e:motion`) never exceeds 2.0 km/h.

## Hardware access on macOS

- The computer must be within a few metres of the treadmill.
- The treadmill accepts one connection at a time and stops advertising while connected. Stop the probe before
  running end-to-end tests, and vice versa.
- Claude Code's Bash tool has no Bluetooth permission: macOS aborts the process (`abort trap`) or reports the
  adapter state as unknown (`have=0`). Run hardware commands in a terminal that has the permission instead: the
  Claude desktop app's Terminal panel works, as do `!` commands and the user's own terminal app.

## Probe workflow

The user starts `task probe` in their terminal. Logs go to `.probe/<date>_<time>.log` with `HH:MM:SS.mmm`
timestamps. Send commands over HTTP and read the log file:

```bash
task probe:cmd -- mark display shows 2.0 km/h
task probe:cmd -- set 2.0 0
```

Commands: `start [m]`, `stop [m]`, `set <km/h> <incline> [m]` (no range check), `frame <group> <type> <m>
[data…]`, `raw <hex>`, `mark <text>`.

## Camera on macOS

macOS silently blocks the camera for Claude Code: ffmpeg opens the device but never receives a frame. The user
runs `task cam:record` in their own terminal (`CAMERA=<index>` if needed; `task cam:devices` lists cameras),
and Claude reads the photos.

- Photos are written once per second to `.cam/HHMMSS.jpg` (ignored by git), so they line up with probe log
  times.
- The USB camera used so far only works at 640x480.
- The console switches screens every few seconds, so check 10–15 s of photos after each command, not just one.
