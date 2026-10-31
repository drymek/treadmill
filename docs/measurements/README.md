# Measurements

Raw evidence behind [the protocol specification](../protocol.md).

## Log

[`2026-10-31.log`](2026-10-31.log) is the complete session log from 2026-10-31: 3,280 notifications, every
command sent, and notes taken during the sessions. Times are local (`HH:MM:SS.mmm`).

| Kind | Meaning |
|---|---|
| `RX` | notification from `FFF1`; a trailing `=` marks a frame identical to the previous one |
| `TX` | command written to `FFF2`, followed by the command as typed |
| `MARK` | note written during the session (what was done, or what the display showed) |
| `INFO` | connection events |

The original log was recorded by an earlier version of the probe tool. For this copy, the notes were translated
from Polish, the tool's per-frame decoding was removed (it predated the final field layout), and `=` was
recomputed. Frames and timestamps are unchanged.

| Session | Time | Content |
|---|---|---|
| 0 | 08:37–08:48 | idle traffic, stop while idle, console buttons with the belt stopped |
| 1 | 08:51–08:54 | start, speed and incline changes, counters, stop |
| 2 | 09:12–09:17 | unknown command types, speed and incline limits, counter reset |
| 3 | 09:20–09:26 | master byte, reserved bytes, console buttons and heart rate while running |

## Photos

The camera took one photo of the console per second, named `HHMMSS.jpg` after the time it was taken. The
console cycles through screens, so one photo shows only some of the values. A selection:

| Photo | Display | Closest frame | Shows |
|---|---|---|---|
| [`084504`](photos/084504.jpg) | 0.00 · 0.0 · 0:00 | `FD 05 A1 00 00 A6 FE` | idle, compact frame |
| [`084628`](photos/084628.jpg) | P01 · 30:00 | `… 00 14 00 01 01 00 1E …` | program picked on the console: bytes 6–7 hold the program number, byte 9 the duration |
| [`084650`](photos/084650.jpg) | DIST 1.00 | `… 00 64 …` (bytes 11–12) | distance target, 10 m units |
| [`084654`](photos/084654.jpg) | 50.00 (CAL) | `… 01 F4 …` (bytes 13–14) | calorie target, 0.1 kcal units |
| [`085150`](photos/085150.jpg) | countdown "2" · 1.0 | `FD 10 A1 01 0A 05 …` | state *starting* |
| [`085240`](photos/085240.jpg) | INCL 2 · 3.0 · CAL 1.64 | `… 02 1E 1E 02 02 … 00 11 …` | speed, incline; calories 1.7 in 0.1 kcal steps |
| [`085310`](photos/085310.jpg) | INCL 0 · 3.5 · CAL 3.50 | `… 02 23 23 00 00 … 00 23 …` | incline reset by a speed-only command |
| [`085314`](photos/085314.jpg) | DIST 0.05 · 3.5 · 1:22 | `… 01 16 00 05 …` | elapsed time (bytes 9–10) and distance |
| [`091520`](photos/091520.jpg) | INCL 5 · 1.0 | `… 02 0A 0A 05 05 …` | incline target; byte 7 mirrors byte 6 |
| [`092520`](photos/092520.jpg) | INCL 3 · PUL 76 | `… 1F 4C …` (bytes 14–15) | heart rate from the hand grips |
| [`092549`](photos/092549.jpg) | DIST 0.06 · PUL 94 · CAL 4.76 | `… 00 06 00 30 5E …` | distance, calories, heart rate |

The display shows calories with two decimals, while the protocol reports 0.1 kcal steps.

The full set of about 3,000 photos is not in the repository because of its size (270 MB).
