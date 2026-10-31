// Package workout parses and validates workout files (.workout.yaml), the
// planned workouts specified in docs/workout.md.
//
//	w, err := workout.Parse(data)
//	for _, step := range w.Expand() {
//		// send step.Speed and step.Incline, wait for step.Duration or step.Distance
//	}
//
// Parse reports every problem in the file at once, each with its line and
// step position, so the whole file can be fixed in one go.
package workout

import (
	"errors"
	"fmt"
	"strings"
	"time"

	"craftersy.com/treadmill/src/go/lib/protocol"
)

// Version is the format version this package reads.
const Version = 1

// Extension is the file name suffix of workout files.
const Extension = ".workout.yaml"

// Workout is a parsed and validated workout file.
type Workout struct {
	Version     int
	Name        string
	Description string
	// Blocks in file order. A plain step is a block with Repeat 1.
	Blocks []Block
}

// Block is a sequence of steps run Repeat times.
type Block struct {
	Repeat int
	Steps  []Step
}

// Step is one step of a workout.
type Step struct {
	// Name is the step name, or "Step <n>" with n counting steps in file order.
	Name string
	Note string
	// Exactly one of Duration and Distance is set.
	Duration time.Duration
	Distance int // metres
	// Pace is the planned pace per kilometre, zero when the file gives a speed.
	Pace time.Duration
	// Speed is the speed sent to the treadmill: the given speed, or the pace
	// converted and rounded to 0.1 km/h.
	Speed protocol.Speed
	// Incline is the level sent to the treadmill. In Blocks it is only
	// meaningful when InclineSet; Expand fills in inherited levels.
	Incline    protocol.Incline
	InclineSet bool
	// Line is the step's line in the file.
	Line int
}

// ActualPace returns the pace per kilometre that Speed results in, rounded to
// the nearest second.
func (s Step) ActualPace() time.Duration {
	return PaceOf(s.Speed)
}

// PaceOf returns the pace per kilometre at speed, rounded to the nearest second.
func PaceOf(speed protocol.Speed) time.Duration {
	if speed == 0 {
		return 0
	}
	// 3600 s / (speed/10 km/h) = 36000/speed s, rounded half up.
	seconds := (36000*2 + int(speed)) / (2 * int(speed))
	return time.Duration(seconds) * time.Second
}

// Expand returns the steps in execution order, with repeat blocks unrolled and
// every step's Incline resolved: a step without an incline keeps the level of
// the step executed before it, and the first one starts at 0.
func (w *Workout) Expand() []Step {
	var steps []Step
	var incline protocol.Incline
	for _, b := range w.Blocks {
		for range b.Repeat {
			for _, s := range b.Steps {
				if s.InclineSet {
					incline = s.Incline
				}
				s.Incline = incline
				s.InclineSet = true
				steps = append(steps, s)
			}
		}
	}
	return steps
}

// Error is one problem in a workout file.
type Error struct {
	Line int
	// Path locates the step, e.g. "steps[1].steps[0] (Fast)"; empty for
	// document-level problems.
	Path string
	Msg  string
}

func (e *Error) Error() string {
	var b strings.Builder
	if e.Line > 0 {
		fmt.Fprintf(&b, "line %d: ", e.Line)
	}
	if e.Path != "" {
		b.WriteString(e.Path)
		b.WriteString(": ")
	}
	b.WriteString(e.Msg)
	return b.String()
}

// Errors is every problem found in a workout file, in file order.
type Errors []*Error

func (es Errors) Error() string {
	msgs := make([]string, len(es))
	for i, e := range es {
		msgs[i] = e.Error()
	}
	return strings.Join(msgs, "\n")
}

// Unwrap lets errors.As find individual errors.
func (es Errors) Unwrap() []error {
	errs := make([]error, len(es))
	for i, e := range es {
		errs[i] = e
	}
	return errs
}

// AsErrors returns the problems in err, which Parse returned.
func AsErrors(err error) Errors {
	var es Errors
	if errors.As(err, &es) {
		return es
	}
	return Errors{{Msg: err.Error()}}
}
