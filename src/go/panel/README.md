# panel

A virtual HMS console in your terminal. It shows what the treadmill's own display shows, all at once instead of
cycling through screens, and its keys work like the console buttons.

```
 HMS  ·  RZ_TreadMil                                        ● connected
╭────────────────────────────────────────────────────────────────────╮
│        INCLINE             SPEED  km/h              PULSE          │
│           _                   _   _                      _         │
│           _|                 |_  |_                 |  | _|        │
│           _|                  _|. _|                |  ||_         │
│                           belt 4.8 km/h                            │
│                                                                    │
│          TIME              DISTANCE  km            CALORIES        │
│         _   _                       _              _  _            │
│       | _|. _||_|             |   | _|            |_ |_| |_|       │
│       ||_ . _|  |             |.  ||_             |_||_|.  |       │
│                                                                    │
│                             ● RUNNING                              │
╰────────────────────────────────────────────────────────────────────╯
 [enter] START  [space] STOP  [m] −  [p] +  [3 6 9 0] 3 6 9 12 km/h
 [↓] ▽  [↑] △  [a s d f] incline 3 6 9 12  [?] help  [q] quit
```

Built with [Bubble Tea](https://github.com/charmbracelet/bubbletea) and
[Lip Gloss](https://github.com/charmbracelet/lipgloss) on top of the [Go library](../lib).

## Run

```bash
task panel:sim       # try it without hardware, against the built-in simulator
task panel           # connect to the treadmill over Bluetooth
```

From `src/go/panel`: `go run . -sim`, or `go run .` with `-name` / `-address` to pick a device. On macOS the
terminal app needs Bluetooth permission.

## Keys

| Key | Console button | Effect |
|---|---|---|
| `enter` | START | 3-second countdown, then 1.0 km/h |
| `space`, `x` | STOP | brake to a halt |
| `p`, `+`, `→` | + | speed +0.1 km/h |
| `m`, `-`, `←` | − | speed −0.1 km/h |
| `3` `6` `9` `0` | 3 6 9 12 | speed 3, 6, 9, 12 km/h |
| `↑`, `k` / `↓`, `j` | △ / ▽ | incline +1 / −1 |
| `a` `s` `d` `f` | 3% 6% 9% 12% | incline 3, 6, 9, 12 |
| `?` | | help |
| `q`, `esc`, `ctrl+c` | | quit; a moving belt is stopped first (press `q` twice to quit at once) |

`p` and `m` do the same as `+` and `−` without needing Shift.

## How it behaves

- **Every command is confirmed** by the treadmill before the panel reports it. Values the treadmill would
  ignore (below 1.0 km/h, incline below 0) are refused with a message instead of being sent.
- **Commands keep their order.** Pressing `p` five times quickly gives exactly +0.5 km/h, and STOP is never
  overtaken by an earlier command.
- **Changes on the real console** show up on the virtual one within about a second.
- **Connection loss** shows a red banner. The belt keeps its last setting while the panel reconnects, so use the
  console STOP or the safety key.
- **Quitting** stops a moving belt. If the stop cannot be confirmed, the panel says so on exit.

Always attach the safety key. A terminal is not an emergency stop.
