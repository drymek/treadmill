# Workout File Format

| | |
|---|---|
| **Version** | 1 |
| **Date** | 2026-10-31 |
| **Status** | Draft. |
| **Reference implementation** | [`src/go/tools/workout/lib`](../src/go/tools/workout/lib); validator `task workout -- <file>` |
| **Application** | [`src/kotlin/workout`](../src/kotlin/workout), the Android app that designs and runs workouts |
| **File extension** | `.workout.yaml` |

This document specifies a file that describes a planned treadmill workout: a list of steps, each with a pace or
speed, an incline and a length in time or distance. An application loads the file, shows the plan and, once the
user starts it, sends each step to the treadmill using the commands in [`protocol.md`](protocol.md).

## Contents

1. [Example](#1-example)
2. [Document](#2-document)
3. [Steps](#3-steps)
4. [Value formats](#4-value-formats)
5. [Validation](#5-validation)
6. [Execution](#6-execution)
7. [Future versions](#7-future-versions)

## 1. Example

```yaml
version: 1
name: 3-minute intervals
steps:
  - name: Warm-up
    duration: 10m
    pace: 6:30/km
    incline: 1
  - repeat: 5
    steps:
      - { name: Fast, duration: 3m, pace: 4:30/km, incline: 2, note: Keep your cadence up }
      - { name: Jog, distance: 400m, pace: 7:00/km }   # incline stays at 2
  - name: Walk
    duration: 2m
    speed: 5 km/h
  - name: Cool-down
    duration: 10m
    pace: 6:30/km
    incline: 0
```

The workout lasts 37 minutes plus the five 400 m jogs, then the treadmill stops.

## 2. Document

The file is YAML 1.2, encoded as UTF-8. The top level is a mapping:

| Key | Required | Type | Meaning |
|---|---|---|---|
| `version` | yes | integer | format version; this document specifies `1` |
| `name` | yes | string | workout name shown in the application |
| `description` | no | string | free text shown before the workout starts |
| `steps` | yes | list of [steps](#3-steps) or [repeat blocks](#32-repeat-block) | at least one entry |

Unknown keys are an error, so a typo such as `inclin` is reported instead of ignored. A key that appears twice
in the same mapping (the document, a step or a repeat block) is an error too, even though some YAML parsers
silently keep the last value.

## 3. Steps

### 3.1 Step

| Key | Required | Type | Meaning |
|---|---|---|---|
| `name` | no | string | shown during the step; defaults to `Step <n>` |
| `duration` | one of `duration`, `distance` | [duration](#41-duration) | length of the step in time |
| `distance` | one of `duration`, `distance` | [distance](#42-distance) | length of the step in distance |
| `pace` | one of `pace`, `speed` | [pace](#43-pace) | target pace |
| `speed` | one of `pace`, `speed` | [speed](#44-speed) | target speed |
| `incline` | no | integer 0–22 | incline level, as shown on the console; see below |
| `note` | no | string | shown on screen during the step |

A step has exactly one of `duration` and `distance`, and exactly one of `pace` and `speed`.

Without `incline`, a step keeps the incline of the step executed before it. The first step of the workout
without `incline` uses 0. Inside a repeat block, "the step executed before it" is the previous step in execution
order, so on the second and later rounds the first step of the block follows the last step of the previous round.

### 3.2 Repeat block

| Key | Required | Type | Meaning |
|---|---|---|---|
| `repeat` | yes | integer ≥ 2 | number of rounds |
| `steps` | yes | list of [steps](#31-step) | at least one step; a repeat block cannot contain another repeat block |

## 4. Value formats

### 4.1 Duration

One or more of `<n>h`, `<n>m`, `<n>s`, in this order, with non-negative integers and no spaces: `90s`, `3m`,
`1m30s`, `1h5m`. The total is greater than zero. Seconds and minutes may exceed 59 (`90s` is valid).

### 4.2 Distance

`<n>m` with an integer number of metres, or `<x>km` with at most two decimal places: `400m`, `1km`, `2.5km`.
The treadmill counts distance in units of 10 m, so the distance must be a positive multiple of 10 m.

### 4.3 Pace

`<min>:<ss>/km`: minutes without leading zeros, seconds as two digits 00–59, e.g. `6:30/km`, `12:00/km`. The unit
is required; it also keeps YAML from reading `6:30` as anything but a string.

The treadmill sets speed in steps of 0.1 km/h ([protocol 4.4](protocol.md#44-set-speed-and-incline)). A pace of
*p* seconds per kilometre becomes the speed 3600 / *p* km/h, rounded to the nearest 0.1 km/h (halves round up).
The application shows the pace that results from the rounded speed, so the user sees what they will run:

| Planned pace | Exact speed | Sent speed | Actual pace |
|---|---|---|---|
| `6:30/km` | 9.23 km/h | 9.2 km/h | 6:31/km |
| `4:30/km` | 13.33 km/h | 13.3 km/h | 4:31/km |
| `5:00/km` | 12.00 km/h | 12.0 km/h | 5:00/km |

Actual pace is 3600 / speed seconds per kilometre, rounded to the nearest second.

### 4.4 Speed

`<x> km/h` with at most one decimal place, e.g. `5 km/h`, `10.5 km/h`. The space before the unit is optional.

## 5. Validation

The application validates the whole file when loading it and refuses to start a workout with any error. It
reports every error with the step's position (e.g. `steps[1].steps[0]`) and name.

- The rules of sections [2](#2-document)–[4](#4-value-formats) hold: required keys, exclusive pairs, value
  formats, no unknown or duplicate keys, no nested repeat blocks.
- Every speed, after converting pace and rounding, is within 1.0–22.0 km/h, the range the treadmill accepts. In
  pace that is about 2:44/km to 60:00/km.
- Every incline is within 0–22.

## 6. Execution

1. The application shows the plan with the actual pace or speed of every step and the total time (and distance,
   where steps are given in distance). The workout starts only when the user starts it.
2. The application sends *Start*. The first step begins when the treadmill reports that the belt is running,
   after its countdown.
3. At the beginning of each step the application sends *Set speed and incline* with the step's speed and
   incline. The step's time and distance count from this moment. The treadmill accelerates at about 0.5 km/h per
   second, so the first seconds of a faster step are spent reaching its speed (about 8 s from 6:30/km to
   4:30/km).
4. A `duration` step ends when its time has passed on the application's clock. A `distance` step ends when the
   treadmill's distance counter has grown by the step's distance since the step began.
5. After the last step the application sends *Stop*. The treadmill brakes and levels the deck.

If the belt stops for any other reason (the stop button, the safety key, a lost connection), the workout ends
and is not resumed automatically.

## 7. Future versions

Planned for later versions, not part of version 1:

- Ramps: a step whose pace or speed changes gradually from a start value to an end value.
