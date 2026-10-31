# Kotlin library

A Kotlin/JVM client for `RZ_TreadMil` treadmills, ported from the [Go library](../../go/lib). It has no Android
dependency, so it runs in unit tests and on any JVM; the Android BLE transport lives in the
[app](../panel/src/main/kotlin/com/craftersy/treadmill/panel/bluetooth/BleTransport.kt).

| Package | What |
|---|---|
| `com.craftersy.treadmill.protocol` | pure framing: `Command`, `Status`, `Speed`, `Incline`, `State` |
| `com.craftersy.treadmill.client` | `Client` (acknowledged, serialised commands), `Transport`, `ReconnectingTransport` |
| `com.craftersy.treadmill.simulator` | `Simulator`, an in-memory treadmill implementing `Transport` |

```kotlin
val client = Client(Simulator(scope), scope) // or a BLE transport
client.start()                               // 3 s countdown, then 1.0 km/h
client.setSpeed(3.5)
client.setIncline(2)                         // keeps 3.5 km/h
client.status.collect { println(it) }
client.stop()
client.close()
```

Every command waits until a status frame confirms it, as [the specification](../../../docs/protocol.md#71-confirm-every-command)
recommends, and throws `NotAcknowledgedException` otherwise. Values the treadmill would ignore throw
`OutOfRangeException` before anything is sent.

## Tests

```bash
task kotlin:test
```

The protocol tests use frames captured from the device and decode every notification and command in
[`docs/measurements/2026-10-31.log`](../../../docs/measurements/2026-10-31.log).

## Safety

The treadmill keeps its last setting when the connection drops, so the belt keeps running. Never rely on
Bluetooth to stop the belt; always attach the safety key.
