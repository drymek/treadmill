# treadmill — Go library for RZ_TreadMil Bluetooth treadmills

[![CI](https://github.com/drymek/treadmill/actions/workflows/ci.yml/badge.svg)](https://github.com/drymek/treadmill/actions/workflows/ci.yml)
[![Go Reference](https://pkg.go.dev/badge/craftersy.com/treadmill/src/go/lib.svg)](https://pkg.go.dev/craftersy.com/treadmill/src/go/lib)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](../../../LICENSE)

Control a Bluetooth Low Energy treadmill from Go: start and stop the belt, set speed and incline,
and stream live status (speed, incline, time, distance, calories and heart rate).

The protocol was **reverse-engineered on real hardware**. Every command was sent one at a time while a camera
recorded the treadmill display, so each byte in this library is backed by a log line and a photo.

```go
tm := treadmill.New(transport)
tm.Start(ctx)
tm.SetSpeed(ctx, 5.5)
tm.SetIncline(ctx, 3) // keeps 5.5 km/h
tm.Stop(ctx)
```

## Contents

- [Features](#features)
- [Compatibility](#compatibility)
- [Installation](#installation)
- [Quick start](#quick-start)
- [Usage](#usage)
- [Packages](#packages)
- [Testing](#testing)
- [Protocol in a nutshell](#protocol-in-a-nutshell)
- [Safety](#safety)
- [Known limitations](#known-limitations)
- [Contributing](#contributing)
- [License](#license)

## Features

- **Acknowledged commands.** Each command waits for a status frame that confirms it. The treadmill silently
  ignores values it does not like, so getting no error from a write proves nothing.
- **No surprise resets.** On the wire, speed and incline always travel together. `SetSpeed` keeps the current
  incline and `SetIncline` keeps the current speed.
- **Validation that matches the device.** Out-of-range values are rejected before they reach the treadmill,
  which would ignore them.
- **Survives dropouts.** `DialReconnecting` reconnects with backoff and tells you when the link drops, because
  the belt keeps running without it.
- **Live status.** You can poll `Status()`, `Subscribe()` to a channel or `WaitFor()` a condition.
- **Simulator included.** `treadmilltest.Simulator` behaves like the measured device, so apps and CI run without
  hardware.
- **Pure protocol package.** `protocol` has no dependencies and no I/O, so you can use it with any BLE stack
  (TinyGo, gomobile, your own).
- **Tested on three levels.** Unit tests use captured frames, fuzzers cover both decoders, and end-to-end tests
  run against the physical treadmill.

## Compatibility

| | |
|---|---|
| Device | Treadmills advertising as `RZ_TreadMil` with GATT service `FFF0` (tested on an HMS-branded treadmill) |
| OS | macOS (CoreBluetooth), Linux (BlueZ HCI) |
| Go | 1.26 or newer |

If your treadmill uses the same app but behaves differently, please open an issue and attach a probe log.
[CONTRIBUTING.md](../../../CONTRIBUTING.md#recording-a-protocol-log) explains how to record one.

## Installation

```bash
go get craftersy.com/treadmill/src/go/lib
```

On **macOS**, the program that runs your binary (Terminal, iTerm, your IDE) needs Bluetooth permission. Grant it
in System Settings → Privacy & Security → Bluetooth.

On **Linux**, the HCI backend needs raw socket access. Run as root or grant the capability:

```bash
sudo setcap 'cap_net_raw,cap_net_admin+eip' ./your-binary
```

## Quick start

```go
package main

import (
	"context"
	"fmt"
	"log"
	"time"

	"craftersy.com/treadmill/src/go/lib/bluetooth"
	"craftersy.com/treadmill/src/go/lib/protocol"
	"craftersy.com/treadmill/src/go/lib/treadmill"
)

func main() {
	ctx := context.Background()

	tr, err := bluetooth.Dial(ctx, bluetooth.Options{}) // scans for "RZ_TreadMil"
	if err != nil {
		log.Fatal(err)
	}
	tm := treadmill.New(tr)
	defer tm.Close()

	if err := tm.Start(ctx); err != nil {
		log.Fatal(err)
	}
	// 3-second countdown, then the belt runs at 1.0 km/h.
	tm.WaitFor(ctx, func(s protocol.Status) bool { return s.State == protocol.StateRunning })

	if err := tm.SetSpeed(ctx, 4.5); err != nil {
		log.Fatal(err)
	}

	watch, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()
	for st := range tm.Subscribe(watch) {
		fmt.Printf("%v  %v  %4d m  %.1f kcal  %d bpm\n",
			st.Elapsed, st.CurrentSpeed, st.Distance, st.Calories, st.HeartRate)
	}

	if err := tm.Stop(ctx); err != nil {
		log.Fatal(err)
	}
}
```

## Usage

### Commands

| Method | Effect | Acknowledged when |
|---|---|---|
| `Start(ctx)` | 3 s countdown, then 1.0 km/h; resets time, distance and calories | state is `starting` or `running` |
| `Stop(ctx)` | brakes to a halt; incline returns to 0 | state is `stopping`, `stopped` or `idle` |
| `SetSpeed(ctx, kmh)` | target speed, 1.0–22.0 km/h in 0.1 steps | target speed and incline match |
| `SetIncline(ctx, level)` | target incline, 0–22 | target speed and incline match |
| `SetSpeedIncline(ctx, kmh, level)` | both in one command | target speed and incline match |

`Set*` methods require a running belt and return `ErrNotRunning` otherwise. Errors to check with `errors.Is`:

| Error | Meaning |
|---|---|
| `protocol.ErrOutOfRange` | value outside what the treadmill accepts; nothing was sent |
| `treadmill.ErrNotRunning` | speed or incline change while the belt is not running |
| `treadmill.ErrNoStatus` | no status frame received yet; wait with `WaitFor` |
| `treadmill.ErrNotAcknowledged` | the treadmill did not confirm in time (default 3 s, see `WithAckTimeout`) |
| `treadmill.ErrDisconnected` | the connection dropped and `DialReconnecting` is trying again |
| `treadmill.ErrClosed` | the connection has ended |

### Status

The treadmill sends a status every ~1.3 s (every ~0.65 s while braking) and right after each command.

| Field | Unit | Notes |
|---|---|---|
| `State` | `idle`, `starting`, `running`, `stopping`, `stopped` | `stopped` is sent once with the final metrics, then `idle` |
| `TargetSpeed` | 0.1 km/h (`Speed`) | changes as soon as a command is accepted |
| `CurrentSpeed` | 0.1 km/h (`Speed`) | actual belt speed; reaches the target in about 2 s |
| `Incline` | level | target incline; the device does not report the motor position |
| `Elapsed` | `time.Duration` | whole seconds |
| `Distance` | metres | 10 m resolution |
| `Calories` | kcal | 0.1 kcal resolution |
| `HeartRate` | bpm | from the hand grip sensors; 0 without contact |
| `Compact` | bool | the short idle frame, which carries no metrics |
| `Raw` | bytes | the frame as received |

Changes made on the treadmill's own panel (buttons, quick keys) show up in the status like any other change.

### Waiting for things

```go
// Block until the belt actually reaches 6 km/h.
_, err := tm.WaitFor(ctx, func(s protocol.Status) bool { return s.CurrentSpeed >= 60 })
```

### Connection loss

The treadmill keeps running at its last setting when Bluetooth drops. Wrap the dial function to reconnect
automatically and to learn about every change:

```go
tr, err := treadmill.DialReconnecting(ctx,
	func(ctx context.Context) (treadmill.Transport, error) {
		return bluetooth.Dial(ctx, bluetooth.Options{})
	},
	treadmill.WithBackoff(time.Second, 30*time.Second),
	treadmill.WithConnectionHandler(func(e treadmill.ConnectionEvent) {
		log.Printf("treadmill %v (attempt %d) %v", e.State, e.Attempt, e.Err)
	}),
)
if err != nil {
	log.Fatal(err)
}
tm := treadmill.New(tr)
```

While disconnected, commands fail with `treadmill.ErrDisconnected` and `Status()` returns the last status
received. Without the wrapper, `Client.Done()` is closed when the connection ends.

### Options

```go
tm := treadmill.New(tr,
	treadmill.WithAckTimeout(5*time.Second), // 0 = fire and forget
	treadmill.WithLogger(slog.Default()),    // logs frames that fail to decode (debug level)
)
```

## Packages

| Package | Purpose | Dependencies |
|---|---|---|
| [`protocol`](protocol) | Encode commands, decode status frames, value types and limits | stdlib only |
| [`treadmill`](treadmill) | High-level client over any `Transport`, reconnecting transport | `protocol` |
| [`bluetooth`](bluetooth) | BLE `Transport` (scan, connect, subscribe) | [go-ble/ble](https://github.com/go-ble/ble) |
| [`treadmilltest`](treadmilltest) | In-memory simulator implementing `Transport` | `protocol` |

You can bring your own transport by implementing three methods:

```go
type Transport interface {
	Write(ctx context.Context, frame []byte) error
	Frames() <-chan []byte // closed when the connection ends
	Close() error
}
```

## Testing

All commands also exist as tasks in the [repository Taskfile](../../../Taskfile.yml) (`task --list`).

```bash
go test -race ./...                            # unit tests and simulator, no hardware needed
go test -run=^$ -fuzz=FuzzParseStatus ./protocol
```

**End-to-end tests** run against a physical treadmill. They are behind the `e2e` build tag and two opt-in
variables:

```bash
TREADMILL_E2E=1 go test -tags e2e -v ./e2e                          # belt stays still
TREADMILL_E2E=1 TREADMILL_E2E_MOTION=1 go test -tags e2e -v ./e2e   # belt moves, max 2.0 km/h
```

Select the device with `TREADMILL_NAME` or `TREADMILL_ADDRESS`. The motion test stops the belt even when it
fails. Only one BLE client can be connected at a time, so close the vendor app and the probe tool first.

## Protocol in a nutshell

Service `FFF0`; commands are written to `FFF2`, status frames arrive as notifications on `FFF1`.

```
command  FB LEN GROUP TYPE MASTER DATA… CHK FC      LEN = 3 + len(DATA)
status   FD LEN A1 PAYLOAD…           CHK FE        LEN = frame length − 2
CHK      low byte of the sum from LEN to the last data byte
```

| Command | Frame |
|---|---|
| start | `FB 03 A2 01 01 A7 FC` |
| stop | `FB 03 A2 04 01 AA FC` |
| speed 3.0 km/h, incline 2 | `FB 07 A1 02 01 1E 02 00 00 CB FC` |

Idle: `FD 05 A1 00 00 A6 FE`. Running at 3.0 km/h, incline 2, 1:00 elapsed:
`FD 10 A1 02 1E 1E 02 02 00 01 00 00 03 00 17 00 0E FE`

| Byte | Value above | Meaning |
|---|---|---|
| 3 | `02` | state |
| 4 | `1E` | target speed ×0.1 km/h |
| 5 | `1E` | current speed ×0.1 km/h |
| 6–7 | `02 02` | incline (byte 7 mirrors byte 6) |
| 8 | `00` | unknown, always `00` |
| 9–10 | `01 00` | minutes, seconds |
| 11–12 | `00 03` | distance ×10 m, big-endian |
| 13–14 | `00 17` | calories ×0.1 kcal, big-endian |
| 15 | `00` | heart rate, bpm |

The full specification, with every open question and the measurement log, is in
[`docs/protocol.md`](../../../docs/protocol.md); the raw data is in [`docs/measurements`](../../../docs/measurements).

## Safety

A treadmill is a machine that can hurt people. This library sends commands that move the belt.

- Always use the **physical safety key**. The library cannot stop the belt if the connection drops.
- Commands are acknowledged, but **the treadmill keeps its last setting after a disconnect**. Design apps so
  that losing Bluetooth is never dangerous.
- Run motion tests only with the safety key attached and nobody walking on the belt.

The software is provided as is, without warranty. You use it at your own risk.

## Known limitations

- The treadmill has no pause command. Unknown command types are ignored.
- The actual incline motor position is not reported, only the target.
- The meaning of status byte 8 (always `00` so far, possibly hours) is unknown.
- Right after an unusual command, the first status frame can briefly report wrong values even though its
  checksum is correct. The library sends only well-formed, known commands.

## Contributing

Issues and pull requests are welcome, especially logs from other treadmill models. The repository includes
[`probe`](../tools/probe), a tool that logs every notification with timestamps and sends any command, so a session
can be lined up with photos of the display. [CONTRIBUTING.md](../../../CONTRIBUTING.md) explains the workflow
and the release process.

Before sending a pull request, run:

```bash
task go:check
```

## License

[MIT](../../../LICENSE) © Marcin Dryka
