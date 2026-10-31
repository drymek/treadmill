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

// dialer hands out simulators and lets tests fail dials on demand.
type dialer struct {
	mu    sync.Mutex
	sims  []*treadmilltest.Simulator
	fails int
}

func (d *dialer) dial(context.Context) (treadmill.Transport, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.fails > 0 {
		d.fails--
		return nil, errors.New("out of range")
	}
	s := treadmilltest.NewSimulator(tick)
	d.sims = append(d.sims, s)
	return s, nil
}

func (d *dialer) last() *treadmilltest.Simulator {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.sims[len(d.sims)-1]
}

func (d *dialer) setFails(n int) {
	d.mu.Lock()
	defer d.mu.Unlock()
	d.fails = n
}

type events struct {
	ch chan treadmill.ConnectionEvent
}

func newEvents() *events { return &events{ch: make(chan treadmill.ConnectionEvent, 32)} }

func (e *events) handle(ev treadmill.ConnectionEvent) { e.ch <- ev }

func (e *events) next(t *testing.T) treadmill.ConnectionEvent {
	t.Helper()
	select {
	case ev := <-e.ch:
		return ev
	case <-time.After(2 * time.Second):
		t.Fatal("no connection event")
	}
	return treadmill.ConnectionEvent{}
}

func TestReconnectsAfterConnectionLoss(t *testing.T) {
	d := &dialer{}
	ev := newEvents()
	tr, err := treadmill.DialReconnecting(context.Background(), d.dial,
		treadmill.WithBackoff(time.Millisecond, 4*time.Millisecond), treadmill.WithConnectionHandler(ev.handle))
	if err != nil {
		t.Fatal(err)
	}
	c := treadmill.New(tr)
	defer c.Close()

	if e := ev.next(t); e.State != treadmill.Connected || e.Attempt != 0 {
		t.Fatalf("first event %+v", e)
	}

	d.setFails(2)
	_ = d.last().Close() // the treadmill goes out of range

	if e := ev.next(t); e.State != treadmill.Disconnected {
		t.Fatalf("got %+v, want disconnected", e)
	}
	for i := 1; i <= 2; i++ {
		if e := ev.next(t); e.State != treadmill.ReconnectFailed || e.Attempt != i || e.Err == nil {
			t.Fatalf("got %+v, want failed attempt %d", e, i)
		}
	}
	if e := ev.next(t); e.State != treadmill.Connected || e.Attempt != 3 {
		t.Fatalf("got %+v, want connected on attempt 3", e)
	}
	if !tr.Connected() {
		t.Error("Connected() = false after reconnect")
	}

	// The client keeps working on the new connection.
	if err := c.Start(ctx(t)); err != nil {
		t.Fatalf("Start after reconnect: %v", err)
	}
	if _, err := c.WaitFor(ctx(t), func(s protocol.Status) bool { return s.State == protocol.StateRunning }); err != nil {
		t.Fatal(err)
	}
}

func TestWriteWhileDisconnected(t *testing.T) {
	d := &dialer{}
	ev := newEvents()
	tr, err := treadmill.DialReconnecting(context.Background(), d.dial,
		treadmill.WithBackoff(time.Hour, time.Hour), treadmill.WithConnectionHandler(ev.handle))
	if err != nil {
		t.Fatal(err)
	}
	defer tr.Close()
	ev.next(t)
	_ = d.last().Close()
	ev.next(t)

	if tr.Connected() {
		t.Error("Connected() = true while disconnected")
	}
	if err := tr.Write(context.Background(), protocol.Start()); !errors.Is(err, treadmill.ErrDisconnected) {
		t.Errorf("Write = %v, want ErrDisconnected", err)
	}
}

func TestFirstDialErrorIsReturned(t *testing.T) {
	d := &dialer{fails: 1}
	if _, err := treadmill.DialReconnecting(context.Background(), d.dial); err == nil {
		t.Fatal("expected error")
	}
}

func TestCloseStopsReconnecting(t *testing.T) {
	d := &dialer{}
	tr, err := treadmill.DialReconnecting(context.Background(), d.dial, treadmill.WithBackoff(time.Millisecond, time.Millisecond))
	if err != nil {
		t.Fatal(err)
	}
	if err := tr.Close(); err != nil {
		t.Fatal(err)
	}
	for range tr.Frames() {
	}
	if err := tr.Write(context.Background(), protocol.Start()); !errors.Is(err, treadmill.ErrClosed) {
		t.Errorf("Write after Close = %v, want ErrClosed", err)
	}
	if err := d.last().Write(context.Background(), protocol.Start()); !errors.Is(err, treadmilltest.ErrClosed) {
		t.Errorf("underlying connection still open: %v", err)
	}
}

func TestContextEndStopsTransport(t *testing.T) {
	d := &dialer{}
	cctx, cancel := context.WithCancel(context.Background())
	tr, err := treadmill.DialReconnecting(cctx, d.dial)
	if err != nil {
		t.Fatal(err)
	}
	cancel()
	select {
	case <-drain(tr.Frames()):
	case <-time.After(2 * time.Second):
		t.Fatal("frames channel not closed after context end")
	}
}

func drain(ch <-chan []byte) <-chan struct{} {
	done := make(chan struct{})
	go func() {
		for range ch {
		}
		close(done)
	}()
	return done
}

func TestConnectionStateString(t *testing.T) {
	if treadmill.ReconnectFailed.String() != "reconnect failed" || treadmill.ConnectionState(9).String() != "unknown" {
		t.Error("unexpected ConnectionState strings")
	}
}
