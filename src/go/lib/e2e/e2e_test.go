//go:build e2e

// Package e2e runs the library against a physical treadmill.
//
// Tests that keep the belt still run with:
//
//	TREADMILL_E2E=1 go test -tags e2e -v ./e2e
//
// Tests that move the belt (never faster than 2.0 km/h) also need
// TREADMILL_E2E_MOTION=1. Run them only with the safety key attached and
// nobody on the belt, or someone standing on the side rails.
//
// TREADMILL_NAME and TREADMILL_ADDRESS select the device (defaults to the
// advertised name RZ_TreadMil).
package e2e

import (
	"context"
	"os"
	"testing"
	"time"

	"craftersy.com/treadmill/src/go/lib/bluetooth"
	"craftersy.com/treadmill/src/go/lib/protocol"
	"craftersy.com/treadmill/src/go/lib/treadmill"
)

// statusInterval is how often the device reports, plus margin.
const statusInterval = 2 * time.Second

func connect(t *testing.T) *treadmill.Client {
	t.Helper()
	if os.Getenv("TREADMILL_E2E") != "1" {
		t.Skip("set TREADMILL_E2E=1 to run against a physical treadmill")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 40*time.Second)
	defer cancel()
	tr, err := bluetooth.Dial(ctx, bluetooth.Options{
		Name:    os.Getenv("TREADMILL_NAME"),
		Address: os.Getenv("TREADMILL_ADDRESS"),
	})
	if err != nil {
		t.Fatalf("Dial: %v", err)
	}
	t.Logf("connected to %s (%s)", tr.Name(), tr.Address())
	tm := treadmill.New(tr)
	t.Cleanup(func() { _ = tm.Close() })
	return tm
}

func requireMotion(t *testing.T) {
	t.Helper()
	if os.Getenv("TREADMILL_E2E_MOTION") != "1" {
		t.Skip("set TREADMILL_E2E_MOTION=1 to run tests that move the belt")
	}
}

func timeout(t *testing.T, d time.Duration) context.Context {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), d)
	t.Cleanup(cancel)
	return ctx
}

func waitState(t *testing.T, tm *treadmill.Client, want protocol.State, d time.Duration) protocol.Status {
	t.Helper()
	st, err := tm.WaitFor(timeout(t, d), func(s protocol.Status) bool { return s.State == want })
	if err != nil {
		t.Fatalf("waiting for state %v: %v", want, err)
	}
	return st
}

// stopOnExit makes sure a failing test never leaves the belt running.
func stopOnExit(t *testing.T, tm *treadmill.Client) {
	t.Cleanup(func() {
		if err := tm.Stop(timeout(t, 10*time.Second)); err != nil {
			t.Errorf("stopping belt on exit: %v", err)
		}
	})
}

func TestReceivesStatusPeriodically(t *testing.T) {
	tm := connect(t)
	sub := tm.Subscribe(timeout(t, 3*statusInterval))
	var got []protocol.Status
	for st := range sub {
		got = append(got, st)
		if len(got) == 2 {
			break
		}
	}
	if len(got) < 2 {
		t.Fatalf("received %d status frames in %v, want at least 2", len(got), 3*statusInterval)
	}
	t.Logf("first status: %v (% X)", got[0].State, got[0].Raw)
}

func TestStopWhileIdleIsAcknowledged(t *testing.T) {
	tm := connect(t)
	if _, err := tm.WaitFor(timeout(t, statusInterval), func(protocol.Status) bool { return true }); err != nil {
		t.Fatal(err)
	}
	if st, _ := tm.Status(); st.State != protocol.StateIdle {
		t.Skipf("treadmill not idle (%v)", st.State)
	}
	if err := tm.Stop(timeout(t, 5*time.Second)); err != nil {
		t.Fatal(err)
	}
}

func TestSetWhileIdleIsRejected(t *testing.T) {
	tm := connect(t)
	if _, err := tm.WaitFor(timeout(t, statusInterval), func(protocol.Status) bool { return true }); err != nil {
		t.Fatal(err)
	}
	if err := tm.SetSpeed(timeout(t, 5*time.Second), 2); err == nil {
		t.Fatal("SetSpeed on a stopped belt succeeded")
	}
}

func TestWorkout(t *testing.T) {
	requireMotion(t)
	tm := connect(t)
	stopOnExit(t, tm)

	if err := tm.Start(timeout(t, 5*time.Second)); err != nil {
		t.Fatalf("Start: %v", err)
	}
	st := waitState(t, tm, protocol.StateRunning, 10*time.Second)
	if st.TargetSpeed != protocol.MinSpeed {
		t.Errorf("speed after start = %v, want %v", st.TargetSpeed, protocol.MinSpeed)
	}

	t.Run("speed step of 0.1 km/h", func(t *testing.T) {
		if err := tm.SetSpeed(timeout(t, 5*time.Second), 1.1); err != nil {
			t.Fatal(err)
		}
	})

	t.Run("belt ramps to target speed", func(t *testing.T) {
		if err := tm.SetSpeed(timeout(t, 5*time.Second), 2.0); err != nil {
			t.Fatal(err)
		}
		if _, err := tm.WaitFor(timeout(t, 10*time.Second), func(s protocol.Status) bool { return s.CurrentSpeed == 20 }); err != nil {
			t.Fatalf("current speed never reached 2.0 km/h: %v", err)
		}
	})

	t.Run("incline keeps speed", func(t *testing.T) {
		if err := tm.SetIncline(timeout(t, 5*time.Second), 2); err != nil {
			t.Fatal(err)
		}
		if st, _ := tm.Status(); st.TargetSpeed != 20 || st.Incline != 2 {
			t.Errorf("got %v / incline %d, want 2.0 km/h / incline 2", st.TargetSpeed, st.Incline)
		}
	})

	t.Run("speed keeps incline", func(t *testing.T) {
		if err := tm.SetSpeed(timeout(t, 5*time.Second), 1.5); err != nil {
			t.Fatal(err)
		}
		if st, _ := tm.Status(); st.TargetSpeed != 15 || st.Incline != 2 {
			t.Errorf("got %v / incline %d, want 1.5 km/h / incline 2", st.TargetSpeed, st.Incline)
		}
	})

	t.Run("metrics count up", func(t *testing.T) {
		st, err := tm.WaitFor(timeout(t, 20*time.Second), func(s protocol.Status) bool { return s.Elapsed >= 15*time.Second })
		if err != nil {
			t.Fatal(err)
		}
		t.Logf("elapsed %v, distance %d m, calories %.1f kcal", st.Elapsed, st.Distance, st.Calories)
	})

	t.Run("stop brakes and reports final metrics", func(t *testing.T) {
		if err := tm.Stop(timeout(t, 5*time.Second)); err != nil {
			t.Fatal(err)
		}
		st := waitState(t, tm, protocol.StateStopped, 15*time.Second)
		if st.CurrentSpeed != 0 || st.Elapsed < 15*time.Second {
			t.Errorf("stopped frame: speed %v, elapsed %v", st.CurrentSpeed, st.Elapsed)
		}
		waitState(t, tm, protocol.StateIdle, 2*statusInterval)
	})
}
