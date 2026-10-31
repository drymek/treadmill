package treadmill

import (
	"context"
	"errors"
	"sync"
	"time"
)

// ErrDisconnected is returned by ReconnectingTransport.Write while the
// connection is down.
var ErrDisconnected = errors.New("treadmill: disconnected, reconnecting")

// ConnectionState is reported to the handler set with WithConnectionHandler.
type ConnectionState int

const (
	// Connected: a connection has been established (initially or again).
	Connected ConnectionState = iota
	// Disconnected: the connection dropped; reconnection attempts follow.
	Disconnected
	// ReconnectFailed: one reconnection attempt failed; Err says why.
	ReconnectFailed
)

func (s ConnectionState) String() string {
	switch s {
	case Connected:
		return "connected"
	case Disconnected:
		return "disconnected"
	case ReconnectFailed:
		return "reconnect failed"
	}
	return "unknown"
}

// ConnectionEvent describes a change of the connection.
type ConnectionEvent struct {
	State   ConnectionState
	Attempt int   // reconnection attempt number, starting at 1; 0 for the first connection
	Err     error // set for ReconnectFailed
}

// ReconnectOption configures DialReconnecting.
type ReconnectOption func(*ReconnectingTransport)

// WithBackoff sets the delay between reconnection attempts. It starts at min
// and doubles up to max. Defaults: 1 s and 30 s.
func WithBackoff(min, max time.Duration) ReconnectOption {
	return func(r *ReconnectingTransport) { r.minDelay, r.maxDelay = min, max }
}

// WithConnectionHandler sets a function called on every connection change.
// It runs on the transport's goroutine and must not block.
func WithConnectionHandler(fn func(ConnectionEvent)) ReconnectOption {
	return func(r *ReconnectingTransport) { r.onChange = fn }
}

// ReconnectingTransport is a Transport that dials again whenever the
// connection drops, until its context ends or Close is called.
//
// The treadmill keeps running at its last setting while the connection is
// down. Use WithConnectionHandler to react, for example by warning the user.
// While disconnected, Write fails with ErrDisconnected and Client commands fail
// with it as well; Client.Status keeps the last status received until the
// next frame arrives.
type ReconnectingTransport struct {
	dial     func(context.Context) (Transport, error)
	minDelay time.Duration
	maxDelay time.Duration
	onChange func(ConnectionEvent)

	ctx    context.Context
	cancel context.CancelFunc
	frames chan []byte
	done   chan struct{}

	mu  sync.Mutex
	cur Transport
}

// DialReconnecting dials once and returns an error if that fails. Afterwards
// it reconnects in the background. ctx bounds the whole lifetime of the
// transport, reconnections included.
//
//	tr, err := treadmill.DialReconnecting(ctx, func(ctx context.Context) (treadmill.Transport, error) {
//		return bluetooth.Dial(ctx, bluetooth.Options{})
//	})
func DialReconnecting(ctx context.Context, dial func(context.Context) (Transport, error), opts ...ReconnectOption) (*ReconnectingTransport, error) {
	first, err := dial(ctx)
	if err != nil {
		return nil, err
	}
	r := &ReconnectingTransport{
		dial:     dial,
		minDelay: time.Second,
		maxDelay: 30 * time.Second,
		onChange: func(ConnectionEvent) {},
		frames:   make(chan []byte, 64),
		done:     make(chan struct{}),
	}
	for _, o := range opts {
		o(r)
	}
	r.ctx, r.cancel = context.WithCancel(ctx)
	go func() {
		<-r.ctx.Done()
		r.mu.Lock()
		defer r.mu.Unlock()
		if r.cur != nil {
			_ = r.cur.Close()
		}
	}()
	go r.run(first)
	return r, nil
}

func (r *ReconnectingTransport) run(tr Transport) {
	defer close(r.done)
	defer close(r.frames)

	attempt := 0
	for {
		if !r.setCurrent(tr) {
			return
		}
		r.onChange(ConnectionEvent{State: Connected, Attempt: attempt})
		for f := range tr.Frames() {
			select {
			case r.frames <- f:
			case <-r.ctx.Done():
			}
		}
		r.setCurrent(nil)
		if r.ctx.Err() != nil {
			return
		}
		r.onChange(ConnectionEvent{State: Disconnected})

		var err error
		delay := r.minDelay
		for attempt = 1; ; attempt++ {
			select {
			case <-time.After(delay):
			case <-r.ctx.Done():
				return
			}
			if tr, err = r.dial(r.ctx); err == nil {
				break
			}
			if r.ctx.Err() != nil {
				return
			}
			r.onChange(ConnectionEvent{State: ReconnectFailed, Attempt: attempt, Err: err})
			delay = min(delay*2, r.maxDelay)
		}
	}
}

// setCurrent swaps the active connection. It refuses (and closes) a new
// connection once the transport is shutting down.
func (r *ReconnectingTransport) setCurrent(tr Transport) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	if tr != nil && r.ctx.Err() != nil {
		_ = tr.Close()
		return false
	}
	r.cur = tr
	return true
}

// Write implements Transport.
func (r *ReconnectingTransport) Write(ctx context.Context, frame []byte) error {
	r.mu.Lock()
	cur := r.cur
	r.mu.Unlock()
	if cur == nil {
		if r.ctx.Err() != nil {
			return ErrClosed
		}
		return ErrDisconnected
	}
	return cur.Write(ctx, frame)
}

// Frames implements Transport. The channel stays open across reconnections
// and closes only after Close or when the context ends.
func (r *ReconnectingTransport) Frames() <-chan []byte { return r.frames }

// Connected reports whether a connection is currently up.
func (r *ReconnectingTransport) Connected() bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.cur != nil
}

// Close stops reconnecting and closes the current connection.
func (r *ReconnectingTransport) Close() error {
	r.cancel()
	<-r.done
	return nil
}
