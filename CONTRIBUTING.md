# Contributing

Thanks for helping. This project reverse-engineers a treadmill protocol, so two kinds of contributions matter
equally: **code** and **measurements**.

## Ways to help

- **Report another treadmill model.** If your treadmill advertises as `RZ_TreadMil` or uses the same app,
  a log from it tells us whether the protocol is shared. See [Recording a protocol log](#recording-a-protocol-log).
- **Answer an open question.** [`docs/protocol.md` § 8](docs/protocol.md#8-open-questions) lists what is still
  unknown and how to find out.
- **Fix a bug or add a feature** in an existing implementation.
- **Add an implementation in another language** under `src/<language>/lib`.

Before larger changes, open an issue so we can agree on the approach.

## Development setup

You need [Go](https://go.dev/dl/) 1.26+ and [Task](https://taskfile.dev/installation/).

```bash
git clone https://github.com/drymek/treadmill.git
cd treadmill
task --list          # every available command
task go:check        # what CI runs: format check, vet, staticcheck, build, race tests
```

None of the tests need hardware: the library ships with a simulator (`treadmilltest`). End-to-end tests against
a physical treadmill are opt-in (`task go:e2e`, `task go:e2e:motion`); see the
[library README](src/go/lib/README.md#testing).

## Pull requests

1. Fork, create a branch, make the change.
2. Add or update tests. Protocol changes need a captured frame as a test case.
3. Run `task go:check`.
4. Update `src/go/lib/CHANGELOG.md` under **Unreleased** for user-visible changes.
5. Update `docs/protocol.md` when the change comes from a new observation, and add the evidence to the
   measurement log.

Keep commits focused and write messages in the imperative mood ("Add heart rate field").

## Recording a protocol log

The workflow that produced the specification:

1. **Safety first.** Attach the safety key. Never stand on the belt while sending commands you have not tried
   before.
2. **Start the camera** (optional, macOS) and point it at the console:

   ```bash
   task cam:record          # one photo per second into .cam/HHMMSS.jpg
   ```

   On Linux, adapt the ffmpeg command in `Taskfile.yml` to `-f v4l2 -i /dev/video0`.
3. **Start the probe** in another terminal. It needs Bluetooth permission (on macOS, run it from a terminal
   app that has it):

   ```bash
   task probe               # logs every notification to .probe/<date>_<time>.log
   ```

4. **Send commands one at a time** and wait at least 10 seconds between them, so the console cycles through its
   screens:

   ```bash
   task probe:cmd -- mark display shows 0.0 km/h
   task probe:cmd -- start
   task probe:cmd -- set 2.0 0
   task probe:cmd -- stop
   task probe:cmd -- frame A2 02 01      # any command, checksum added
   ```

   Commands can also be typed into the probe's terminal. Use `mark` to note what the display shows.
5. **Open an issue** with the *New device or observation* template, attach the log file and, if you can,
   photos that show the display at the interesting moments. Log line times match the photo file names.

## Releasing (maintainers)

The Go library lives in a subdirectory, so its tags carry the path prefix: `src/go/lib/v0.1.0`.

1. In `src/go/lib/CHANGELOG.md`, rename **Unreleased** to the version and date, e.g.
   `## [0.1.0] - 2026-11-02`, and add a fresh empty **Unreleased** section above it.
2. Commit, then tag (runs `task go:check` first):

   ```bash
   task go:release:tag VERSION=v0.1.0
   git push origin src/go/lib/v0.1.0
   ```

3. Create a GitHub release from the tag with the changelog section as notes.

The module path `craftersy.com/treadmill/src/go/lib` resolves through a `go-import` meta tag. `craftersy.com`
must return it for every path under `/treadmill` requested with `?go-get=1`:

```html
<meta name="go-import" content="craftersy.com/treadmill git https://github.com/drymek/treadmill">
```

## Code of conduct

This project follows the [Contributor Covenant](CODE_OF_CONDUCT.md). By taking part, you agree to uphold it.

## License

By contributing, you agree that your contributions are licensed under the [MIT License](LICENSE).
