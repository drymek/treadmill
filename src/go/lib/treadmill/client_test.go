package treadmill_test

import (
	"context"
	"errors"
	"sync"
	"testing"
	"time"

	"craftersy.com/treadmill/src/go/lib/protocol"
	"craftersy.com/treadmill/src/go/lib/treadmill"
	"craftersy.com/treadmill/src/go/lib/treadmilltest"
)

const tick = 2 * time.Millisecond

func newClient(t *testing.T) (*treadmill.Client, *treadmilltest.Simulator) {
	t.Helper()
	sim := treadmilltest.NewSimulator(tick)
	c := treadmill.New(sim)
	t.Cleanup(func() { _ = c.Close() })
	return c, sim
}

func ctx(t *testing.T) context.Context {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	t.Cleanup(cancel)
	return ctx
}

func running(t *testing.T, c *treadmill.Client) {
	t.Helper()
	if err := c.Start(ctx(t)); err != nil {
		t.Fatalf("Start: %v", err)
	}
	if _, err := c.WaitFor(ctx(t), func(s protocol.Status) bool { return s.State == protocol.StateRunning }); err != nil {
		t.Fatalf("waiting for running: %v", err)
	}
}

func TestStartRunsAtMinimumSpeed(t *testing.T) {
	c, _ := newClient(t)
	running(t, c)
	st, err := c.WaitFor(ctx(t), func(s protocol.Status) bool { return s.CurrentSpeed == protocol.MinSpeed })
	if err != nil {
		t.Fatal(err)
	}
	if st.TargetSpeed != protocol.MinSpeed {
		t.Errorf("target speed %v, want %v", st.TargetSpeed, protocol.MinSpeed)
	}
}

func TestSetSpeedKeepsIncline(t *testing.T) {
	c, _ := newClient(t)
	running(t, c)
	if err := c.SetSpeedIncline(ctx(t), 3.0, 2); err != nil {
		t.Fatal(err)
	}
	if err := c.SetSpeed(ctx(t), 3.5); err != nil {
		t.Fatal(err)
	}
	st, _ := c.Status()
	if st.TargetSpeed != 35 || st.Incline != 2 {
		t.Errorf("got %v / incline %d, want 3.5 km/h / incline 2", st.TargetSpeed, st.Incline)
	}
}

func TestSetInclineKeepsSpeed(t *testing.T) {
	c, _ := newClient(t)
	running(t, c)
	if err := c.SetSpeed(ctx(t), 2.5); err != nil {
		t.Fatal(err)
	}
	if err := c.SetIncline(ctx(t), 4); err != nil {
		t.Fatal(err)
	}
	st, _ := c.Status()
	if st.TargetSpeed != 25 || st.Incline != 4 {
		t.Errorf("got %v / incline %d, want 2.5 km/h / incline 4", st.TargetSpeed, st.Incline)
	}
}

func TestBeltRampsToTargetSpeed(t *testing.T) {
	c, _ := newClient(t)
	running(t, c)
	if err := c.SetSpeed(ctx(t), 4.0); err != nil {
		t.Fatal(err)
	}
	if _, err := c.WaitFor(ctx(t), func(s protocol.Status) bool { return s.CurrentSpeed == 40 }); err != nil {
		t.Fatal(err)
	}
}

func TestStopBrakesToIdle(t *testing.T) {
	c, _ := newClient(t)
	running(t, c)
	if err := c.SetSpeed(ctx(t), 3.0); err != nil {
		t.Fatal(err)
	}
	if _, err := c.WaitFor(ctx(t), func(s protocol.Status) bool { return s.CurrentSpeed == 30 }); err != nil {
		t.Fatal(err)
	}
	if err := c.Stop(ctx(t)); err != nil {
		t.Fatal(err)
	}
	st, err := c.WaitFor(ctx(t), func(s protocol.Status) bool { return s.State == protocol.StateStopped })
	if err != nil {
		t.Fatal(err)
	}
	if st.CurrentSpeed != 0 || st.Elapsed == 0 {
		t.Errorf("stopped frame: speed %v, elapsed %v", st.CurrentSpeed, st.Elapsed)
	}
	if _, err := c.WaitFor(ctx(t), func(s protocol.Status) bool { return s.State == protocol.StateIdle }); err != nil {
		t.Fatal(err)
	}
}

func TestSetRequiresRunningBelt(t *testing.T) {
	c, _ := newClient(t)
	if _, err := c.WaitFor(ctx(t), func(protocol.Status) bool { return true }); err != nil {
		t.Fatal(err)
	}
	for name, err := range map[string]error{
		"SetSpeed":        c.SetSpeed(ctx(t), 3),
		"SetIncline":      c.SetIncline(ctx(t), 1),
		"SetSpeedIncline": c.SetSpeedIncline(ctx(t), 3, 1),
	} {
		if !errors.Is(err, treadmill.ErrNotRunning) {
			t.Errorf("%s error = %v, want ErrNotRunning", name, err)
		}
	}
}

func TestSetRejectsOutOfRangeWithoutWriting(t *testing.T) {
	c, sim := newClient(t)
	running(t, c)
	before := len(sim.Writes())
	for name, err := range map[string]error{
		"speed too low":  c.SetSpeed(ctx(t), 0.9),
		"speed too high": c.SetSpeed(ctx(t), 22.1),
		"incline":        c.SetIncline(ctx(t), 23),
		"negative":       c.SetSpeedIncline(ctx(t), 3, -1),
	} {
		if !errors.Is(err, protocol.ErrOutOfRange) {
			t.Errorf("%s: error = %v, want ErrOutOfRange", name, err)
		}
	}
	if after := len(sim.Writes()); after != before {
		t.Errorf("%d frames written for rejected commands", after-before)
	}
}

func TestNoStatusYet(t *testing.T) {
	c := treadmill.New(newSilentTransport())
	defer c.Close()
	if err := c.SetSpeed(ctx(t), 3); !errors.Is(err, treadmill.ErrNoStatus) {
		t.Errorf("error = %v, want ErrNoStatus", err)
	}
}

func TestUnacknowledgedCommand(t *testing.T) {
	c := treadmill.New(newSilentTransport(), treadmill.WithAckTimeout(20*time.Millisecond))
	defer c.Close()
	if err := c.Start(ctx(t)); !errors.Is(err, treadmill.ErrNotAcknowledged) {
		t.Errorf("error = %v, want ErrNotAcknowledged", err)
	}
}

func TestAckDisabledReturnsAfterWrite(t *testing.T) {
	tr := newSilentTransport()
	c := treadmill.New(tr, treadmill.WithAckTimeout(0))
	defer c.Close()
	if err := c.Start(ctx(t)); err != nil {
		t.Fatal(err)
	}
	if got := tr.writes(); len(got) != 1 || string(got[0]) != string(protocol.Start()) {
		t.Errorf("writes = % X", got)
	}
}

func TestWriteErrorIsReturned(t *testing.T) {
	tr := newSilentTransport()
	tr.err = errors.New("radio off")
	c := treadmill.New(tr)
	defer c.Close()
	if err := c.Stop(ctx(t)); !errors.Is(err, tr.err) {
		t.Errorf("error = %v, want %v", err, tr.err)
	}
}

func TestHeartRate(t *testing.T) {
	c, sim := newClient(t)
	running(t, c)
	sim.SetHeartRate(85)
	if _, err := c.WaitFor(ctx(t), func(s protocol.Status) bool { return s.HeartRate == 85 }); err != nil {
		t.Fatal(err)
	}
}

func TestSubscribeEndsWithContext(t *testing.T) {
	c, _ := newClient(t)
	sctx, cancel := context.WithCancel(context.Background())
	sub := c.Subscribe(sctx)
	<-sub
	cancel()
	deadline := time.After(time.Second)
	for {
		select {
		case _, ok := <-sub:
			if !ok {
				return
			}
		case <-deadline:
			t.Fatal("subscription not closed after cancel")
		}
	}
}

func TestCloseEndsSubscriptionsAndCommands(t *testing.T) {
	sim := treadmilltest.NewSimulator(tick)
	c := treadmill.New(sim)
	sub := c.Subscribe(context.Background())
	if err := c.Close(); err != nil {
		t.Fatal(err)
	}
	for range sub {
	}
	<-c.Done()
	if err := c.Start(ctx(t)); !errors.Is(err, treadmill.ErrClosed) {
		t.Errorf("Start after Close: %v, want ErrClosed", err)
	}
	if _, ok := <-c.Subscribe(context.Background()); ok {
		t.Error("Subscribe after Close delivered a status")
	}
}

func TestMalformedFramesAreIgnored(t *testing.T) {
	tr := newSilentTransport()
	c := treadmill.New(tr)
	defer c.Close()
	tr.frames <- []byte{0xFD, 0x05, 0xA1, 0x00, 0x00, 0xFF, 0xFE} // bad checksum
	tr.frames <- []byte{0xFD, 0x05, 0xA1, 0x02, 0x00, 0xA8, 0xFE}
	st, err := c.WaitFor(ctx(t), func(protocol.Status) bool { return true })
	if err != nil || st.State != protocol.StateRunning {
		t.Errorf("got %v, %v; want the valid frame only", st.State, err)
	}
}

// silentTransport records writes and never answers on its own.
type silentTransport struct {
	mu     sync.Mutex
	sent   [][]byte
	err    error
	frames chan []byte
	once   sync.Once
}

func newSilentTransport() *silentTransport { return &silentTransport{frames: make(chan []byte, 8)} }

func (s *silentTransport) Write(_ context.Context, f []byte) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.err != nil {
		return s.err
	}
	s.sent = append(s.sent, f)
	return nil
}

func (s *silentTransport) writes() [][]byte {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.sent
}

func (s *silentTransport) Frames() <-chan []byte { return s.frames }

func (s *silentTransport) Close() error {
	s.once.Do(func() { close(s.frames) })
	return nil
}
