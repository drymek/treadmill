package main

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestRun(t *testing.T) {
	dir := t.TempDir()
	good := filepath.Join(dir, "good.workout.yaml")
	bad := filepath.Join(dir, "bad.workout.yaml")
	write(t, good, "version: 1\nname: Easy\nsteps:\n  - { duration: 10m, pace: 6:00/km, incline: 1 }\n  - { distance: 1km, speed: 10 km/h }\n")
	write(t, bad, "version: 1\nname: Broken\nsteps:\n  - { duration: 1m, pace: 2:00/km }\n")

	var stdout, stderr bytes.Buffer
	if !run(&stdout, &stderr, []string{good}, false) {
		t.Fatalf("good file rejected: %s", stderr.String())
	}
	for _, want := range []string{"OK, Easy", "6:00/km", "10.0 km/h", "1000 m", "total ≈ 16:00, ≈ 2.67 km"} {
		if !strings.Contains(stdout.String(), want) {
			t.Errorf("plan lacks %q:\n%s", want, stdout.String())
		}
	}

	stdout.Reset()
	stderr.Reset()
	if run(&stdout, &stderr, []string{good, bad}, true) {
		t.Fatal("bad file accepted")
	}
	if stdout.Len() != 0 {
		t.Errorf("-q printed a plan:\n%s", stdout.String())
	}
	want := bad + ":4: steps[0]: pace: 2:00/km is 30.0 km/h, outside the treadmill range 1.0 km/h–22.0 km/h\n"
	if stderr.String() != want {
		t.Errorf("got %q, want %q", stderr.String(), want)
	}
}

func write(t *testing.T, path, data string) {
	t.Helper()
	if err := os.WriteFile(path, []byte(data), 0o644); err != nil {
		t.Fatal(err)
	}
}
