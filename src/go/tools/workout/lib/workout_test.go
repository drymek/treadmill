package workout

import (
	"os"
	"slices"
	"strings"
	"testing"
	"time"

	"craftersy.com/treadmill/src/go/lib/protocol"
)

func TestParseSpecificationExample(t *testing.T) {
	data, err := os.ReadFile("testdata/intervals.workout.yaml")
	if err != nil {
		t.Fatal(err)
	}
	w, err := Parse(data)
	if err != nil {
		t.Fatalf("Parse: %v", err)
	}
	if w.Version != 1 || w.Name != "3-minute intervals" || len(w.Blocks) != 4 {
		t.Fatalf("got version %d, name %q, %d blocks", w.Version, w.Name, len(w.Blocks))
	}
	if b := w.Blocks[1]; b.Repeat != 5 || len(b.Steps) != 2 {
		t.Fatalf("repeat block: got %d × %d steps", b.Repeat, len(b.Steps))
	}

	steps := w.Expand()
	if len(steps) != 13 {
		t.Fatalf("Expand: got %d steps, want 13", len(steps))
	}
	type want struct {
		name     string
		duration time.Duration
		distance int
		speed    protocol.Speed
		incline  protocol.Incline
	}
	wants := []want{
		{"Warm-up", 10 * time.Minute, 0, 92, 1},
		{"Fast", 3 * time.Minute, 0, 133, 2},
		{"Jog", 0, 400, 86, 2},
	}
	for range 4 {
		wants = append(wants, want{"Fast", 3 * time.Minute, 0, 133, 2}, want{"Jog", 0, 400, 86, 2})
	}
	wants = append(wants,
		want{"Walk", 2 * time.Minute, 0, 50, 2},
		want{"Cool-down", 10 * time.Minute, 0, 92, 0},
	)
	for i, s := range steps {
		got := want{s.Name, s.Duration, s.Distance, s.Speed, s.Incline}
		if got != wants[i] {
			t.Errorf("step %d: got %+v, want %+v", i, got, wants[i])
		}
	}
	if steps[1].Note != "Keep your cadence up" {
		t.Errorf("note: got %q", steps[1].Note)
	}
	if steps[0].Pace != 390*time.Second {
		t.Errorf("planned pace: got %v", steps[0].Pace)
	}
}

func TestPaceRounding(t *testing.T) {
	// The table in docs/workout.md, section 4.3.
	tests := []struct {
		pace   string
		speed  protocol.Speed
		actual time.Duration
	}{
		{"6:30/km", 92, 6*time.Minute + 31*time.Second},
		{"4:30/km", 133, 4*time.Minute + 31*time.Second},
		{"5:00/km", 120, 5 * time.Minute},
		{"2:44/km", 220, 2*time.Minute + 44*time.Second},
		{"60:00/km", 10, 60 * time.Minute},
	}
	for _, tt := range tests {
		_, speed, err := parsePace(tt.pace)
		if err != nil {
			t.Errorf("%s: %v", tt.pace, err)
			continue
		}
		if speed != tt.speed || PaceOf(speed) != tt.actual {
			t.Errorf("%s: got %v (%v), want %v (%v)", tt.pace, speed, PaceOf(speed), tt.speed, tt.actual)
		}
	}
	for _, pace := range []string{"2:43/km", "70:00/km", "6:30", "6:60/km", "06:30/km", "6:3/km"} {
		if _, _, err := parsePace(pace); err == nil {
			t.Errorf("%s: no error", pace)
		}
	}
}

func TestValueFormats(t *testing.T) {
	durations := map[string]time.Duration{
		"90s": 90 * time.Second, "3m": 3 * time.Minute, "1m30s": 90 * time.Second, "1h5m": 65 * time.Minute,
	}
	for s, want := range durations {
		if got, err := parseDuration(s); err != nil || got != want {
			t.Errorf("duration %q: got %v, %v", s, got, err)
		}
	}
	for _, s := range []string{"", "0s", "3", "3 m", "30s1m", "1.5m"} {
		if _, err := parseDuration(s); err == nil {
			t.Errorf("duration %q: no error", s)
		}
	}

	distances := map[string]int{"400m": 400, "1km": 1000, "2.5km": 2500, "1.25km": 1250}
	for s, want := range distances {
		if got, err := parseDistance(s); err != nil || got != want {
			t.Errorf("distance %q: got %v, %v", s, got, err)
		}
	}
	for _, s := range []string{"0m", "405m", "1.255km", "1.005km", "400", "1 km"} {
		if _, err := parseDistance(s); err == nil {
			t.Errorf("distance %q: no error", s)
		}
	}

	speeds := map[string]protocol.Speed{"5 km/h": 50, "10.5 km/h": 105, "22km/h": 220, "1.0 km/h": 10}
	for s, want := range speeds {
		if got, err := parseSpeed(s); err != nil || got != want {
			t.Errorf("speed %q: got %v, %v", s, got, err)
		}
	}
	for _, s := range []string{"0.9 km/h", "22.1 km/h", "10.55 km/h", "10", "10 kmh"} {
		if _, err := parseSpeed(s); err == nil {
			t.Errorf("speed %q: no error", s)
		}
	}
}

func TestInclineInheritance(t *testing.T) {
	w, err := Parse([]byte(`
version: 1
name: Hills
steps:
  - { duration: 1m, speed: 5 km/h }
  - repeat: 2
    steps:
      - { duration: 1m, speed: 5 km/h }
      - { duration: 1m, speed: 5 km/h, incline: 4 }
`))
	if err != nil {
		t.Fatal(err)
	}
	var got []protocol.Incline
	var names []string
	for _, s := range w.Expand() {
		got = append(got, s.Incline)
		names = append(names, s.Name)
	}
	// The second round's first step follows the first round's last step.
	if want := []protocol.Incline{0, 0, 4, 4, 4}; !slices.Equal(got, want) {
		t.Errorf("inclines: got %v, want %v", got, want)
	}
	if want := "Step 1,Step 2,Step 3,Step 2,Step 3"; strings.Join(names, ",") != want {
		t.Errorf("names: got %v, want %v", names, want)
	}
}

func TestParseReportsEveryError(t *testing.T) {
	_, err := Parse([]byte(`version: 2
name: Broken
colour: red
steps:
  - name: Both
    duration: 1m
    distance: 1km
    pace: 5:00/km
  - name: Neither
    incline: 23
  - repeat: 1
    steps:
      - { duration: 1m, pace: 2:00/km }
      - repeat: 2
        steps: [{ duration: 1m, speed: 5 km/h }]
  - { duration: 1m, speed: 5, inclin: 2 }
`))
	if err == nil {
		t.Fatal("no error")
	}
	want := []string{
		`line 1: unsupported version 2 (this reader supports 1)`,
		`line 3: unknown key "colour"`,
		`line 7: steps[0] (Both): a step has either duration or distance, not both`,
		`line 9: steps[1] (Neither): missing duration or distance`,
		`line 9: steps[1] (Neither): missing pace or speed`,
		`line 10: steps[1] (Neither): incline 23 is outside 0–22`,
		`line 11: steps[2]: repeat must be at least 2, got 1`,
		`line 13: steps[2].steps[0]: pace: 2:00/km is 30.0 km/h, outside the treadmill range 1.0 km/h–22.0 km/h`,
		`line 14: steps[2].steps[1]: a repeat block cannot contain another repeat block`,
		`line 16: steps[3]: unknown key "inclin"`,
		`line 16: steps[3]: speed: "5" is not like 10.5 km/h`,
	}
	got := AsErrors(err)
	if len(got) != len(want) {
		t.Fatalf("got %d errors, want %d:\n%v", len(got), len(want), err)
	}
	for i := range want {
		if got[i].Error() != want[i] {
			t.Errorf("error %d:\n got %s\nwant %s", i, got[i], want[i])
		}
	}
}

func TestParseRejectsMalformedDocuments(t *testing.T) {
	for name, doc := range map[string]string{
		"empty":        ``,
		"not mapping":  `- 1`,
		"bad yaml":     "name: [",
		"no steps":     "version: 1\nname: x",
		"empty steps":  "version: 1\nname: x\nsteps: []",
		"string ver":   "version: \"1\"\nname: x\nsteps: [{duration: 1m, speed: 5 km/h}]",
		"null name":    "version: 1\nname:\nsteps: [{duration: 1m, speed: 5 km/h}]",
		"scalar step":  "version: 1\nname: x\nsteps: [3m]",
		"duplicate":    "version: 1\nname: x\nname: y\nsteps: [{duration: 1m, speed: 5 km/h}]",
		"float incl":   "version: 1\nname: x\nsteps: [{duration: 1m, speed: 5 km/h, incline: 1.5}]",
		"empty repeat": "version: 1\nname: x\nsteps: [{repeat: 2, steps: []}]",
	} {
		if _, err := Parse([]byte(doc)); err == nil {
			t.Errorf("%s: no error", name)
		}
	}
}
