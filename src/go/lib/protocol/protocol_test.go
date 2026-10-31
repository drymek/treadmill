package protocol

import (
	"bytes"
	"encoding/hex"
	"errors"
	"math"
	"reflect"
	"strings"
	"testing"
	"time"
)

// h decodes "FD-05-A1-..." notation used in the measurement log.
func h(t testing.TB, s string) []byte {
	t.Helper()
	b, err := hex.DecodeString(strings.ReplaceAll(s, "-", ""))
	if err != nil {
		t.Fatalf("bad hex %q: %v", s, err)
	}
	return b
}

func TestCommandsMatchCapturedFrames(t *testing.T) {
	mustSet := func(s Speed, i Incline) []byte {
		b, err := SetSpeedIncline(s, i)
		if err != nil {
			t.Fatal(err)
		}
		return b
	}
	tests := []struct {
		name string
		got  []byte
		want string
	}{
		{"start", Start(), "FB-03-A2-01-01-A7-FC"},
		{"stop", Stop(), "FB-03-A2-04-01-AA-FC"},
		{"set 2.0 km/h, incline 0", mustSet(20, 0), "FB-07-A1-02-01-14-00-00-00-BF-FC"},
		{"set 3.0 km/h, incline 2", mustSet(30, 2), "FB-07-A1-02-01-1E-02-00-00-CB-FC"},
		{"set 3.5 km/h, incline 0", mustSet(35, 0), "FB-07-A1-02-01-23-00-00-00-CE-FC"},
		{"set 22.0 km/h, incline 0", mustSet(220, 0), "FB-07-A1-02-01-DC-00-00-00-87-FC"},
		{"start as slave", EncodeCommand(GroupControl, TypeStart, 0x00), "FB-03-A2-01-00-A6-FC"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if want := h(t, tt.want); !bytes.Equal(tt.got, want) {
				t.Errorf("got % X, want % X", tt.got, want)
			}
		})
	}
}

func TestSetSpeedInclineRejectsValuesTheTreadmillIgnores(t *testing.T) {
	for _, tc := range []struct {
		s Speed
		i Incline
	}{{9, 0}, {221, 0}, {0, 0}, {30, 23}, {30, 255}} {
		if _, err := SetSpeedIncline(tc.s, tc.i); !errors.Is(err, ErrOutOfRange) {
			t.Errorf("SetSpeedIncline(%d, %d) error = %v, want ErrOutOfRange", tc.s, tc.i, err)
		}
	}
}

func TestSpeedFromKMH(t *testing.T) {
	ok := map[float64]Speed{1.0: 10, 1.04: 10, 0.95: 10, 3.5: 35, 12.345: 123, 22.0: 220, 22.04: 220}
	for kmh, want := range ok {
		got, err := SpeedFromKMH(kmh)
		if err != nil || got != want {
			t.Errorf("SpeedFromKMH(%v) = %v, %v; want %v", kmh, got, err, want)
		}
	}
	for _, kmh := range []float64{0, 0.9, 0.94, 22.05, 25, -1, math.NaN(), math.Inf(1)} {
		if _, err := SpeedFromKMH(kmh); !errors.Is(err, ErrOutOfRange) {
			t.Errorf("SpeedFromKMH(%v) error = %v, want ErrOutOfRange", kmh, err)
		}
	}
	if s := Speed(35); s.KMH() != 3.5 || s.String() != "3.5 km/h" {
		t.Errorf("Speed(35) = %v / %q", s.KMH(), s.String())
	}
}

func TestInclineFromLevel(t *testing.T) {
	for _, l := range []int{0, 1, 12, 22} {
		if got, err := InclineFromLevel(l); err != nil || int(got) != l {
			t.Errorf("InclineFromLevel(%d) = %v, %v", l, got, err)
		}
	}
	for _, l := range []int{-1, 23, 256} {
		if _, err := InclineFromLevel(l); !errors.Is(err, ErrOutOfRange) {
			t.Errorf("InclineFromLevel(%d) error = %v, want ErrOutOfRange", l, err)
		}
	}
}

func TestDecodeCommandRoundTrip(t *testing.T) {
	in := Command{Group: GroupSettings, Type: TypeSetSpeedIncline, Master: MasterApp, Data: []byte{0x1E, 0x02, 0, 0}}
	out, err := DecodeCommand(in.Encode())
	if err != nil {
		t.Fatal(err)
	}
	if out.Group != in.Group || out.Type != in.Type || out.Master != in.Master || !bytes.Equal(out.Data, in.Data) {
		t.Errorf("round trip: got %+v, want %+v", out, in)
	}
}

func TestDecodeCommandRejectsMalformed(t *testing.T) {
	for _, s := range []string{
		"",
		"FB-03-A2-01-01-A7",          // no end byte
		"FB-03-A2-01-01-A8-FC",       // bad checksum
		"FB-04-A2-01-01-A8-FC",       // bad length
		"FD-03-A2-01-01-A7-FC",       // status start byte
		"FB-07-A1-02-01-1E-02-CB-FC", // truncated data
	} {
		if _, err := DecodeCommand(h(t, s)); !errors.Is(err, ErrMalformed) {
			t.Errorf("DecodeCommand(%s) error = %v, want ErrMalformed", s, err)
		}
	}
}

// Frames below were captured from a physical treadmill; times refer to the
// measurement log in docs/protocol.md.
func TestParseStatusCapturedFrames(t *testing.T) {
	tests := []struct {
		name  string
		frame string
		want  Status
	}{
		{
			name:  "idle",
			frame: "FD-05-A1-00-00-A6-FE",
			want:  Status{State: StateIdle, Compact: true},
		},
		{
			name:  "start acknowledgement",
			frame: "FD-05-A1-01-03-AA-FE",
			want:  Status{State: StateStarting, Compact: true},
		},
		{
			name:  "countdown",
			frame: "FD-10-A1-01-0A-05-00-00-00-00-00-00-00-00-00-00-C1-FE",
			want:  Status{State: StateStarting, TargetSpeed: 10, CurrentSpeed: 5},
		},
		{
			name:  "ramping to 2.0 km/h",
			frame: "FD-10-A1-02-14-0D-00-00-00-00-16-00-00-00-04-00-EE-FE",
			want:  Status{State: StateRunning, TargetSpeed: 20, CurrentSpeed: 13, Elapsed: 22 * time.Second, Calories: 0.4},
		},
		{
			name:  "3.0 km/h, incline 2, past one minute",
			frame: "FD-10-A1-02-1E-1E-02-02-00-01-00-00-03-00-17-00-0E-FE",
			want: Status{State: StateRunning, TargetSpeed: 30, CurrentSpeed: 30, Incline: 2,
				Elapsed: time.Minute, Distance: 30, Calories: 2.3},
		},
		{
			name:  "heart rate 85 bpm",
			frame: "FD-10-A1-02-1E-1E-03-03-00-02-00-00-06-00-2C-55-7E-FE",
			want: Status{State: StateRunning, TargetSpeed: 30, CurrentSpeed: 30, Incline: 3,
				Elapsed: 2 * time.Minute, Distance: 60, Calories: 4.4, HeartRate: 85},
		},
		{
			name:  "braking",
			frame: "FD-10-A1-04-1A-1A-00-00-00-02-12-00-07-00-37-00-3B-FE",
			want: Status{State: StateStopping, TargetSpeed: 26, CurrentSpeed: 26,
				Elapsed: 2*time.Minute + 18*time.Second, Distance: 70, Calories: 5.5},
		},
		{
			name:  "stopped with final metrics",
			frame: "FD-10-A1-05-00-00-00-00-00-01-2D-00-07-00-35-00-20-FE",
			want:  Status{State: StateStopped, Elapsed: 105 * time.Second, Distance: 70, Calories: 5.3},
		},
		{
			name:  "program P02 picked on the panel",
			frame: "FD-10-A1-00-14-00-02-02-00-1E-00-00-00-00-00-00-E7-FE",
			want:  Status{State: StateIdle, TargetSpeed: 20, Incline: 2, Elapsed: 30 * time.Minute},
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			frame := h(t, tt.frame)
			got, err := ParseStatus(frame)
			if err != nil {
				t.Fatalf("ParseStatus: %v", err)
			}
			if !bytes.Equal(got.Raw, frame) {
				t.Errorf("Raw = % X, want % X", got.Raw, frame)
			}
			got.Raw = nil
			if !reflect.DeepEqual(got, tt.want) {
				t.Errorf("got  %+v\nwant %+v", got, tt.want)
			}
		})
	}
}

func TestParseStatusCopiesInput(t *testing.T) {
	frame := h(t, "FD-05-A1-00-00-A6-FE")
	st, _ := ParseStatus(frame)
	frame[3] = 0xFF
	if st.Raw[3] != 0x00 {
		t.Error("Status.Raw aliases the input slice")
	}
}

func TestParseStatusRejectsMalformed(t *testing.T) {
	for _, s := range []string{
		"",
		"FD-05",
		"FD-05-A1-00-00-A7-FE",    // bad checksum
		"FD-06-A1-00-00-A6-FE",    // bad length byte
		"FB-05-A1-00-00-A6-FE",    // command start byte
		"FD-05-A1-00-00-A6-FC",    // command end byte
		"FD-05-A2-00-00-A7-FE",    // unknown code
		"FD-06-A1-00-00-00-A7-FE", // unsupported length
		"FD-10-A1-02-14-0D-00-00-00-00-16-00-00-00-04-00-EF-FE", // full frame, bad checksum
	} {
		if _, err := ParseStatus(h(t, s)); !errors.Is(err, ErrMalformed) {
			t.Errorf("ParseStatus(%s) error = %v, want ErrMalformed", s, err)
		}
	}
}

func TestStateString(t *testing.T) {
	if StateRunning.String() != "running" || State(0x42).String() != "state(0x42)" {
		t.Errorf("unexpected State strings: %v, %v", StateRunning, State(0x42))
	}
}

func FuzzParseStatus(f *testing.F) {
	f.Add(h(f, "FD-05-A1-00-00-A6-FE"))
	f.Add(h(f, "FD-10-A1-02-1E-1E-03-03-00-02-00-00-06-00-2C-55-7E-FE"))
	f.Fuzz(func(t *testing.T, b []byte) {
		st, err := ParseStatus(b)
		if err == nil && !bytes.Equal(st.Raw, b) {
			t.Errorf("Raw differs from input")
		}
	})
}

func FuzzDecodeCommand(f *testing.F) {
	f.Add(Start())
	f.Add(h(f, "FB-07-A1-02-01-1E-02-00-00-CB-FC"))
	f.Fuzz(func(t *testing.T, b []byte) {
		c, err := DecodeCommand(b)
		if err == nil && !bytes.Equal(c.Encode(), b) {
			t.Errorf("re-encoded % X, input % X", c.Encode(), b)
		}
	})
}
