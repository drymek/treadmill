package treadmilltest

import (
	"context"
	"testing"
	"time"

	"craftersy.com/treadmill/src/go/lib/protocol"
)

// next returns the next decoded status, failing after a second.
func next(t *testing.T, s *Simulator) protocol.Status {
	t.Helper()
	select {
	case f := <-s.Frames():
		st, err := protocol.ParseStatus(f)
		if err != nil {
			t.Fatalf("simulator sent invalid frame % X: %v", f, err)
		}
		return st
	case <-time.After(time.Second):
		t.Fatal("no frame")
	}
	return protocol.Status{}
}

func until(t *testing.T, s *Simulator, cond func(protocol.Status) bool) protocol.Status {
	t.Helper()
	for i := 0; i < 500; i++ {
		if st := next(t, s); cond(st) {
			return st
		}
	}
	t.Fatal("condition never met")
	return protocol.Status{}
}

func write(t *testing.T, s *Simulator, f []byte) {
	t.Helper()
	if err := s.Write(context.Background(), f); err != nil {
		t.Fatal(err)
	}
}

func startedSimulator(t *testing.T) *Simulator {
	s := NewSimulator(time.Millisecond)
	t.Cleanup(func() { _ = s.Close() })
	if st := next(t, s); st.State != protocol.StateIdle || !st.Compact {
		t.Fatalf("first frame %+v, want compact idle", st)
	}
	write(t, s, protocol.Start())
	if st := next(t, s); st.State != protocol.StateStarting || !st.Compact {
		t.Fatalf("start acknowledgement %+v, want compact starting", st)
	}
	until(t, s, func(st protocol.Status) bool { return st.State == protocol.StateRunning })
	return s
}

func TestIgnoresWhatTheDeviceIgnores(t *testing.T) {
	s := startedSimulator(t)
	ignored := [][]byte{
		protocol.EncodeCommand(protocol.GroupSettings, protocol.TypeSetSpeedIncline, protocol.MasterApp, 9, 0, 0, 0),
		protocol.EncodeCommand(protocol.GroupSettings, protocol.TypeSetSpeedIncline, protocol.MasterApp, 221, 0, 0, 0),
		protocol.EncodeCommand(protocol.GroupSettings, protocol.TypeSetSpeedIncline, protocol.MasterApp, 30, 23, 0, 0),
		protocol.EncodeCommand(protocol.GroupSettings, protocol.TypeSetSpeedIncline, 0x00, 30, 0, 0, 0),
		protocol.EncodeCommand(protocol.GroupControl, protocol.TypeStop, 0x00),
		{0x01, 0x02},
	}
	for _, f := range ignored {
		write(t, s, f)
	}
	for i := 0; i < 5; i++ {
		if st := next(t, s); st.State != protocol.StateRunning || st.TargetSpeed != protocol.MinSpeed {
			t.Fatalf("ignored command changed state: %+v", st)
		}
	}
}

func TestSlaveStartStartsTheBelt(t *testing.T) {
	s := NewSimulator(time.Millisecond)
	defer s.Close()
	write(t, s, protocol.EncodeCommand(protocol.GroupControl, protocol.TypeStart, 0x00))
	until(t, s, func(st protocol.Status) bool { return st.State == protocol.StateRunning })
}

func TestStopWhileIdleAnswersStopped(t *testing.T) {
	s := NewSimulator(time.Millisecond)
	defer s.Close()
	next(t, s)
	write(t, s, protocol.Stop())
	until(t, s, func(st protocol.Status) bool { return st.State == protocol.StateStopped })
	until(t, s, func(st protocol.Status) bool { return st.State == protocol.StateIdle })
}

func TestCountersAndRestart(t *testing.T) {
	s := startedSimulator(t)
	set, _ := protocol.SetSpeedIncline(220, 0)
	write(t, s, set)
	st := until(t, s, func(st protocol.Status) bool { return st.Distance >= 100 })
	if st.Elapsed == 0 {
		t.Errorf("elapsed not counting: %+v", st)
	}
	write(t, s, protocol.Stop())
	until(t, s, func(st protocol.Status) bool { return st.State == protocol.StateIdle })
	write(t, s, protocol.Start())
	st = until(t, s, func(st protocol.Status) bool { return !st.Compact })
	if st.Elapsed != 0 || st.Distance != 0 {
		t.Errorf("counters not reset on start: %+v", st)
	}
}

func TestWriteAfterClose(t *testing.T) {
	s := NewSimulator(time.Millisecond)
	_ = s.Close()
	_ = s.Close()
	if err := s.Write(context.Background(), protocol.Start()); err != ErrClosed {
		t.Errorf("Write after Close = %v, want ErrClosed", err)
	}
	for range s.Frames() {
	}
}
