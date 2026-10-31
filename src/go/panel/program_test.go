package main

import (
	"context"
	"io"
	"sync"
	"testing"
	"time"

	tea "charm.land/bubbletea/v2"

	"craftersy.com/treadmill/src/go/lib/protocol"
	"craftersy.com/treadmill/src/go/lib/treadmill"
)

// TestProgramWithRawKeys runs the whole Bubble Tea program and types raw
// terminal bytes, the way a user's keyboard would.
func TestProgramWithRawKeys(t *testing.T) {
	in, keys := io.Pipe()
	var (
		mu     sync.Mutex
		client *treadmill.Client
	)
	sim := simulatorConnector(20 * time.Millisecond)
	connect := func(ctx context.Context, ev chan<- treadmill.ConnectionEvent) (*treadmill.Client, error) {
		c, err := sim(ctx, ev)
		mu.Lock()
		client = c
		mu.Unlock()
		return c, err
	}
	p := tea.NewProgram(newModel("simulator", connect),
		tea.WithInput(in), tea.WithOutput(io.Discard), tea.WithWindowSize(80, 30))

	done := make(chan tea.Model, 1)
	go func() {
		m, err := p.Run()
		if err != nil {
			t.Errorf("Run: %v", err)
		}
		done <- m
	}()

	var last protocol.Status
	type_ := func(s string, wait time.Duration) {
		t.Helper()
		if _, err := keys.Write([]byte(s)); err != nil {
			t.Fatalf("typing %q: %v", s, err)
		}
		time.Sleep(wait)
	}

	time.Sleep(300 * time.Millisecond)
	type_("\r", 500*time.Millisecond)     // START; countdown is 3 ticks
	type_("ppp", 300*time.Millisecond)    // +0.3 km/h
	type_("a", 300*time.Millisecond)      // incline 3
	type_("\x1b[A", 300*time.Millisecond) // arrow up: incline 4
	type_("m", 300*time.Millisecond)      // −0.1 km/h
	mu.Lock()
	last, _ = client.Status()
	mu.Unlock()
	type_("q", 0)

	select {
	case final := <-done:
		m := final.(model)
		if last.State != protocol.StateRunning || last.TargetSpeed != 12 || last.Incline != 4 {
			t.Errorf("before quitting: %v, target %v, incline %d; want running, 1.2 km/h, incline 4",
				last.State, last.TargetSpeed, last.Incline)
		}
		if st, _ := m.client.Status(); st.State == protocol.StateRunning {
			t.Errorf("belt still running after quit")
		}
		_ = m.client.Close()
	case <-time.After(5 * time.Second):
		p.Kill()
		t.Fatal("program did not quit")
	}
}
