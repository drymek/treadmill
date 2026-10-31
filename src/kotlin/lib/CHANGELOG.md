# Changelog

All notable changes to the Kotlin library are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the library uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

First version, a port of the Go library without its BLE transport.

### Added

- `protocol`: command encoding (`Command.start`, `stop`, `setSpeedIncline`, `encodeCommand`), command decoding,
  and status decoding (`Status.parse`) for compact and full frames, with checksum and length validation.
- `protocol`: `Speed` and `Incline` types with the ranges the device accepts (1.0–22.0 km/h, 0–22).
- `client`: `Client` with acknowledged, serialised commands, `setSpeed`/`setIncline` that keep the other value,
  `status` (`StateFlow`), `statuses` (`SharedFlow`) and `waitFor`.
- `client`: `ReconnectingTransport` that reconnects with backoff and reports connection events.
- `simulator`: `Simulator` implementing `Transport`, modelled on the measured device.
