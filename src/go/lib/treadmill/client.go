// Package treadmill is a high-level client for RZ_TreadMil treadmills.
//
// A Client wraps a Transport (see the bluetooth package for the real one and
// treadmilltest for a simulator), keeps the latest status, fans it out to
// subscribers and waits for the treadmill to acknowledge every command.
//
//	tr, err := bluetooth.Dial(ctx, bluetooth.Options{})
//	if err != nil { ... }
//	tm := treadmill.New(tr)
//	defer tm.Close()
//
//	err = tm.Start(ctx)
//	err = tm.SetSpeed(ctx, 3.5)
//	err = tm.SetIncline(ctx, 2) // keeps 3.5 km/h
//	err = tm.Stop(ctx)
package treadmill

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"sync"
	"time"

	"craftersy.com/treadmill/src/go/lib/protocol"
)

// Transport moves raw frames between the client and the treadmill.
type Transport interface {
	// Write sends one command frame.
	Write(ctx context.Context, frame []byte) error
	// Frames delivers status notifications as received. The channel must be
	// closed when the connection ends, including after Close.
	Frames() <-chan []byte
	// Close ends the connection.
	Close() error
}

var (
	// ErrNoStatus: the treadmill has not sent a status frame yet.
	ErrNoStatus = errors.New("treadmill: no status received yet")
	// ErrNotRunning: speed and incline can only be changed while the belt runs.
	ErrNotRunning = errors.New("treadmill: belt is not running")
	// ErrNotAcknowledged: no status frame confirmed the command in time.
	ErrNotAcknowledged = errors.New("treadmill: command not acknowledged")
	// ErrClosed: the connection has ended.
	ErrClosed = errors.New("treadmill: connection closed")
)

// DefaultAckTimeout is how long commands wait for confirmation. The treadmill
// answers within about 100 ms and sends a status frame every 1.3 s.
const DefaultAckTimeout = 3 * time.Second

// Option configures a Client.
type Option func(*Client)

// WithAckTimeout sets how long commands wait for confirmation; 0 disables
// waiting, so commands return as soon as the frame is written.
func WithAckTimeout(d time.Duration) Option { return func(c *Client) { c.ackTimeout = d } }

// WithLogger sets the logger used for frames that fail to decode.
func WithLogger(l *slog.Logger) Option { return func(c *Client) { c.log = l } }

// Client controls one treadmill. It is safe for concurrent use; commands are
// serialised.
type Client struct {
	tr         Transport
	ackTimeout time.Duration
	log        *slog.Logger

	cmdMu sync.Mutex

	mu        sync.Mutex
	status    protocol.Status
	hasStatus bool
	closed    bool
	subs      map[chan protocol.Status]struct{}
	done      chan struct{}
}

// New starts a client on top of an open transport.
func New(tr Transport, opts ...Option) *Client {
	c := &Client{
		tr:         tr,
		ackTimeout: DefaultAckTimeout,
		log:        slog.New(slog.DiscardHandler),
		subs:       make(map[chan protocol.Status]struct{}),
		done:       make(chan struct{}),
	}
	for _, o := range opts {
		o(c)
	}
	go c.readLoop()
	return c
}

func (c *Client) readLoop() {
	defer close(c.done)
	for frame := range c.tr.Frames() {
		st, err := protocol.ParseStatus(frame)
		if err != nil {
			c.log.Debug("ignoring frame", "frame", fmt.Sprintf("% X", frame), "err", err)
			continue
		}
		c.publish(st)
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	c.closed = true
	for ch := range c.subs {
		close(ch)
	}
	c.subs = nil
}

func (c *Client) publish(st protocol.Status) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.status, c.hasStatus = st, true
	for ch := range c.subs {
		select {
		case ch <- st:
		default: // slow subscriber: drop rather than block the connection
		}
	}
}

// Close closes the transport and waits for the client to shut down.
func (c *Client) Close() error {
	err := c.tr.Close()
	<-c.done
	return err
}

// Done is closed when the connection ends.
func (c *Client) Done() <-chan struct{} { return c.done }

// Status returns the latest status and whether any has been received.
func (c *Client) Status() (protocol.Status, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.status, c.hasStatus
}

// Subscribe returns a channel with every status received from now on. The
// channel is closed when ctx ends or the connection closes. Statuses are
// dropped for a subscriber that falls more than 16 frames behind.
func (c *Client) Subscribe(ctx context.Context) <-chan protocol.Status {
	ch := make(chan protocol.Status, 16)
	c.mu.Lock()
	if c.closed {
		c.mu.Unlock()
		close(ch)
		return ch
	}
	c.subs[ch] = struct{}{}
	c.mu.Unlock()

	go func() {
		select {
		case <-ctx.Done():
		case <-c.done:
			return
		}
		c.mu.Lock()
		defer c.mu.Unlock()
		if _, ok := c.subs[ch]; ok {
			delete(c.subs, ch)
			close(ch)
		}
	}()
	return ch
}

// WaitFor blocks until a status satisfies cond, starting with the latest one.
func (c *Client) WaitFor(ctx context.Context, cond func(protocol.Status) bool) (protocol.Status, error) {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	sub := c.Subscribe(ctx)
	if st, ok := c.Status(); ok && cond(st) {
		return st, nil
	}
	for {
		select {
		case st, ok := <-sub:
			if !ok {
				if err := ctx.Err(); err != nil {
					return protocol.Status{}, err
				}
				return protocol.Status{}, ErrClosed
			}
			if cond(st) {
				return st, nil
			}
		case <-ctx.Done():
			return protocol.Status{}, ctx.Err()
		}
	}
}

// Start starts the belt and waits for the countdown to begin. The belt runs
// at 1.0 km/h after a 3-second countdown; elapsed time, distance and calories
// restart from zero.
func (c *Client) Start(ctx context.Context) error {
	c.cmdMu.Lock()
	defer c.cmdMu.Unlock()
	return c.send(ctx, protocol.Start(), func(st protocol.Status) bool {
		return st.State == protocol.StateStarting || st.State == protocol.StateRunning
	})
}

// Stop brakes the belt and waits for the treadmill to confirm.
func (c *Client) Stop(ctx context.Context) error {
	c.cmdMu.Lock()
	defer c.cmdMu.Unlock()
	return c.send(ctx, protocol.Stop(), func(st protocol.Status) bool {
		switch st.State {
		case protocol.StateStopping, protocol.StateStopped, protocol.StateIdle:
			return true
		}
		return false
	})
}

// SetSpeed changes the target speed in km/h (1.0–22.0, rounded to 0.1) and
// keeps the current incline.
func (c *Client) SetSpeed(ctx context.Context, kmh float64) error {
	speed, err := protocol.SpeedFromKMH(kmh)
	if err != nil {
		return err
	}
	return c.set(ctx, func(cur protocol.Status) (protocol.Speed, protocol.Incline) { return speed, cur.Incline })
}

// SetIncline changes the incline level (0–22) and keeps the current target
// speed.
func (c *Client) SetIncline(ctx context.Context, level int) error {
	incline, err := protocol.InclineFromLevel(level)
	if err != nil {
		return err
	}
	return c.set(ctx, func(cur protocol.Status) (protocol.Speed, protocol.Incline) { return cur.TargetSpeed, incline })
}

// SetSpeedIncline changes speed and incline in one command.
func (c *Client) SetSpeedIncline(ctx context.Context, kmh float64, level int) error {
	speed, err := protocol.SpeedFromKMH(kmh)
	if err != nil {
		return err
	}
	incline, err := protocol.InclineFromLevel(level)
	if err != nil {
		return err
	}
	return c.set(ctx, func(protocol.Status) (protocol.Speed, protocol.Incline) { return speed, incline })
}

func (c *Client) set(ctx context.Context, target func(protocol.Status) (protocol.Speed, protocol.Incline)) error {
	c.cmdMu.Lock()
	defer c.cmdMu.Unlock()

	cur, ok := c.Status()
	if !ok {
		return ErrNoStatus
	}
	if cur.State != protocol.StateRunning {
		return fmt.Errorf("%w (state %v)", ErrNotRunning, cur.State)
	}
	speed, incline := target(cur)
	frame, err := protocol.SetSpeedIncline(speed, incline)
	if err != nil {
		return err
	}
	return c.send(ctx, frame, func(st protocol.Status) bool {
		return !st.Compact && st.TargetSpeed == speed && st.Incline == incline
	})
}

func (c *Client) send(ctx context.Context, frame []byte, acked func(protocol.Status) bool) error {
	select {
	case <-c.done:
		return ErrClosed
	default:
	}
	if err := c.tr.Write(ctx, frame); err != nil {
		return fmt.Errorf("treadmill: write: %w", err)
	}
	if c.ackTimeout <= 0 {
		return nil
	}
	ackCtx, cancel := context.WithTimeout(ctx, c.ackTimeout)
	defer cancel()
	if _, err := c.WaitFor(ackCtx, acked); err != nil {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		if errors.Is(err, context.DeadlineExceeded) {
			return ErrNotAcknowledged
		}
		return err
	}
	return nil
}
