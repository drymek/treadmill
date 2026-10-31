# Changelog

All notable changes to the Go library are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the library uses
[Semantic Versioning](https://semver.org/). Releases are tagged `src/go/lib/vX.Y.Z`.

## [Unreleased]

First public version.

### Added

- `protocol`: command encoding (`Start`, `Stop`, `SetSpeedIncline`, `EncodeCommand`), command decoding, and
  status decoding (`ParseStatus`) for compact and full frames, with checksum and length validation.
- `protocol`: `Speed` and `Incline` types with the ranges the device accepts (1.0–22.0 km/h, 0–22).
- `treadmill`: `Client` with acknowledged commands, `SetSpeed`/`SetIncline` that keep the other value,
  `Status`, `Subscribe` and `WaitFor`.
- `treadmill`: `DialReconnecting` transport that reconnects with backoff and reports connection events.
- `bluetooth`: BLE transport for macOS and Linux, with scan and connect timeouts.
- `treadmilltest`: simulator implementing `Transport`, modelled on the measured device.
- End-to-end tests against a physical treadmill (`e2e` build tag).

[Unreleased]: https://github.com/drymek/treadmill/commits/main/src/go/lib
