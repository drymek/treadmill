// Command workout validates workout files (.workout.yaml, see
// docs/workout.md) and prints the plan each one describes: every step with
// the speed and incline the treadmill will get, and the estimated total time
// and distance.
//
//	workout [-q] file.workout.yaml...
//
// Errors are printed as file:line: message. The exit status is 1 when any
// file is invalid.
package main

import (
	"flag"
	"fmt"
	"io"
	"os"
	"text/tabwriter"
	"time"

	workout "craftersy.com/treadmill/src/go/tools/workout/lib"
)

func main() {
	quiet := flag.Bool("q", false, "print only errors")
	flag.Usage = func() {
		fmt.Fprintf(flag.CommandLine.Output(), "usage: workout [-q] file.workout.yaml...\n\nflags:\n")
		flag.PrintDefaults()
	}
	flag.Parse()
	if flag.NArg() == 0 {
		flag.Usage()
		os.Exit(2)
	}
	if !run(os.Stdout, os.Stderr, flag.Args(), *quiet) {
		os.Exit(1)
	}
}

// run checks every file and reports whether all of them are valid.
func run(stdout, stderr io.Writer, paths []string, quiet bool) bool {
	valid := true
	for i, path := range paths {
		data, err := os.ReadFile(path)
		if err != nil {
			fmt.Fprintln(stderr, err)
			valid = false
			continue
		}
		w, err := workout.Parse(data)
		if err != nil {
			for _, e := range workout.AsErrors(err) {
				fmt.Fprintf(stderr, "%s\n", location(path, e))
			}
			valid = false
			continue
		}
		if !quiet {
			if i > 0 {
				fmt.Fprintln(stdout)
			}
			printPlan(stdout, path, w)
		}
	}
	return valid
}

func location(path string, e *workout.Error) string {
	loc := path
	if e.Line > 0 {
		loc = fmt.Sprintf("%s:%d", path, e.Line)
	}
	if e.Path != "" {
		return fmt.Sprintf("%s: %s: %s", loc, e.Path, e.Msg)
	}
	return fmt.Sprintf("%s: %s", loc, e.Msg)
}

func printPlan(out io.Writer, path string, w *workout.Workout) {
	fmt.Fprintf(out, "%s: OK, %s\n", path, w.Name)
	if w.Description != "" {
		fmt.Fprintf(out, "%s\n", w.Description)
	}
	fmt.Fprintln(out)

	tw := tabwriter.NewWriter(out, 0, 0, 2, ' ', 0)
	fmt.Fprintln(tw, "#\tstep\tlength\tpace\tspeed\tincline")
	var total time.Duration
	var metres float64
	for i, s := range w.Expand() {
		mps := s.Speed.KMH() / 3.6
		length := ""
		if s.Duration > 0 {
			length = clock(s.Duration)
			total += s.Duration
			metres += mps * s.Duration.Seconds()
		} else {
			length = fmt.Sprintf("%d m", s.Distance)
			total += time.Duration(float64(s.Distance) / mps * float64(time.Second))
			metres += float64(s.Distance)
		}
		pace := clock(s.ActualPace()) + "/km"
		if s.Pace != 0 && s.Pace != s.ActualPace() {
			pace = clock(s.Pace) + " → " + pace
		}
		fmt.Fprintf(tw, "%d\t%s\t%s\t%s\t%v\t%d\n", i+1, s.Name, length, pace, s.Speed, s.Incline)
	}
	tw.Flush()
	fmt.Fprintf(out, "\ntotal ≈ %s, ≈ %.2f km, then stop\n", clock(total.Round(time.Second)), metres/1000)
}

// clock formats d as m:ss, or h:mm:ss from one hour.
func clock(d time.Duration) string {
	s := int(d.Round(time.Second).Seconds())
	if s >= 3600 {
		return fmt.Sprintf("%d:%02d:%02d", s/3600, s/60%60, s%60)
	}
	return fmt.Sprintf("%d:%02d", s/60, s%60)
}
