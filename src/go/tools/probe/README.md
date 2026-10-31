# probe

A protocol research tool. It connects to the treadmill, logs **every** notification with millisecond timestamps
and sends any command you type, including ones the library would refuse. The specification in
[`docs/protocol.md`](../../../../docs/protocol.md) was built with it.

```bash
task probe                                   # from the repository root
go run ./probe -dir .probe                   # from src/go/tools
```

| Flag | Default | Meaning |
|---|---|---|
| `-name` | `RZ_TreadMil` | advertised name to look for |
| `-address` | — | device address; takes precedence over `-name` |
| `-dir` | `.probe` | directory for log files (`<date>_<time>.log`) |
| `-http` | `127.0.0.1:8090` | address for `POST /cmd`; empty disables it |

On macOS the terminal app running the probe needs Bluetooth permission.

## Commands

Type them into the probe's terminal, or post them from anywhere:

```bash
curl -d 'set 2.0 0' http://127.0.0.1:8090/cmd
task probe:cmd -- set 2.0 0
```

| Command | Sends |
|---|---|
| `start [m]` | `A2 01`; `m` is the master byte in hex, default `01` |
| `stop [m]` | `A2 04` |
| `set <km/h> <incline> [m]` | `A1 02` with speed and incline; **no range check** |
| `frame <group> <type> <m> [data…]` | any command, hex bytes, length and checksum added |
| `raw <hex>` | bytes exactly as given |
| `mark <text>` | a note in the log, e.g. what the display shows |

## Log format

```
08:52:12.470 MARK set 2.0 0 sent, display shows 1.0
08:52:12.483 TX   FB-07-A1-02-01-14-00-00-00-BF-FC | set 2.0 0
08:52:12.597 RX   FD-10-A1-02-14-0A-00-00-00-00-15-00-00-00-04-00-EA-FE | running  target 2.0 km/h  current 1.0 km/h …
08:52:13.887 RX   FD-10-A1-02-14-0D-00-00-00-00-16-00-00-00-04-00-EE-FE | running  target 2.0 km/h  current 1.3 km/h …
08:52:15.177 RX   FD-10-A1-02-14-0D-00-00-00-00-16-00-00-00-04-00-EE-FE =
```

A frame identical to the previous one is logged with `=` and not shown on the console. Times match the photo
names written by `task cam:record` (`HHMMSS.jpg`), so a log can be read side by side with the display.

**Safety:** the probe sends whatever it is told, including values the treadmill does not document. Attach the
safety key before sending commands.
