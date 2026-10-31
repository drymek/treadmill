# workout

Validates workout files (`.workout.yaml`, specified in [`docs/workout.md`](../../../../docs/workout.md)) and
prints the plan each one describes: every step with the pace, speed and incline the treadmill will get, and the
estimated total time and distance.

```bash
task workout -- plan.workout.yaml            # from anywhere in the repository
go run ./workout plan.workout.yaml           # from src/go/tools
```

| Flag | Meaning |
|---|---|
| `-q` | print only errors |

Errors are printed as `file:line: step: message`, all of them at once. The exit status is 1 when any file is
invalid, so the tool also works in scripts and CI.

The parser and validator are in [`lib`](lib) (package `workout`): `Parse` reads a file and `Expand` returns
the steps in execution order, with every speed and incline resolved.

```
plan.workout.yaml:4: steps[0] (Fast): pace: 2:00/km is 30.0 km/h, outside the treadmill range 1.0 km/h–22.0 km/h
```
