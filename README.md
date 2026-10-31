# treadmill

[![CI](https://github.com/drymek/treadmill/actions/workflows/ci.yml/badge.svg)](https://github.com/drymek/treadmill/actions/workflows/ci.yml)
[![Go Reference](https://pkg.go.dev/badge/craftersy.com/treadmill/src/go/lib.svg)](https://pkg.go.dev/craftersy.com/treadmill/src/go/lib)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

Open-source control of **RZ_TreadMil** Bluetooth Low Energy treadmills: a reverse-engineered protocol
specification plus client libraries and apps in several languages.

The protocol was decoded on real hardware. Commands went out one by one, every notification was logged with
millisecond timestamps, and a USB camera photographed the treadmill display each second. Each documented byte
is backed by a log line and a photo, and the raw data is part of the repository.

## Implementations

| Language | Path | Kind | Status |
|---|---|---|---|
| Go | [`src/go/lib`](src/go/lib) | library + simulator | ✅ start/stop, speed, incline, live status, heart rate, reconnect |
| Go | [`src/go/panel`](src/go/panel) | terminal app | ✅ virtual HMS console: display, buttons, simulator mode |
| Go | [`src/go/tools/probe`](src/go/tools/probe) | research tool | ✅ logs every notification, sends any command |

New implementations go to `src/<language>/lib` for libraries and `src/<language>/<app>` for applications.

## Protocol

[`docs/protocol.md`](docs/protocol.md) is the specification. In short: GATT service `FFF0`, commands written to
`FFF2` (`FB … FC`), status notified on `FFF1` (`FD … FE`), with a byte-sum checksum. The session log and photos
of the display are in [`docs/measurements`](docs/measurements).

## Repository layout

```
docs/                protocol specification
docs/measurements/   raw session log and photos of the display
src/go/lib/          Go library: protocol, client, BLE transport, simulator, e2e tests
src/go/panel/        terminal app: a virtual HMS console (Bubble Tea)
src/go/tools/probe/  research tool: logs notifications, sends commands over stdin or HTTP
```

## Tasks

Every command the project needs is in [`Taskfile.yml`](Taskfile.yml) ([Task](https://taskfile.dev)):

```bash
task --list
task go:check        # format, vet, staticcheck, build, race tests (what CI runs)
task panel:sim       # the virtual console against the simulator, no hardware needed
task go:e2e          # tests against a physical treadmill (belt stays still)
task probe           # log a session with the probe tool
task cam:record      # photograph the console once per second (macOS)
```

## Contributing

Contributions are welcome, especially logs from other treadmill models. See [CONTRIBUTING.md](CONTRIBUTING.md),
the [Code of Conduct](CODE_OF_CONDUCT.md) and the [security policy](SECURITY.md).

## Safety

This software moves a treadmill belt. Always use the physical safety key, and never rely on a Bluetooth
connection to stop the belt. See the [Go library's safety notes](src/go/lib/README.md#safety).

## License

[MIT](LICENSE) © Marcin Dryka
