// Package bluetooth connects to a treadmill over Bluetooth Low Energy and
// implements treadmill.Transport.
//
// Supported platforms are macOS (CoreBluetooth) and Linux (BlueZ HCI, usually
// requires root or CAP_NET_ADMIN). On macOS the program that runs the code,
// such as the terminal, needs the Bluetooth permission.
package bluetooth

import (
	"context"
	"errors"
	"fmt"
	"net"
	"strings"
	"sync"
	"time"

	"github.com/go-ble/ble"

	"craftersy.com/treadmill/src/go/lib/protocol"
)

// ErrNotFound is returned when no matching treadmill advertises during the scan.
var ErrNotFound = errors.New("bluetooth: treadmill not found")

// Options select the treadmill to connect to.
type Options struct {
	// Name is matched case-insensitively as a substring of the advertised
	// name. Defaults to protocol.DeviceName.
	Name string
	// Address, when set, takes precedence over Name. On Linux it is the MAC
	// address, on macOS the CoreBluetooth peripheral UUID.
	Address string
	// ScanTimeout limits the scan. Defaults to 25 s.
	ScanTimeout time.Duration
	// ConnectTimeout limits establishing the connection. Defaults to 15 s.
	ConnectTimeout time.Duration
}

// Transport is an open BLE connection to a treadmill.
type Transport struct {
	client ble.Client
	write  *ble.Characteristic
	addr   string
	name   string

	mu     sync.Mutex
	closed bool
	frames chan []byte
}

var (
	deviceMu sync.Mutex
	device   ble.Device
)

// defaultDevice opens the adapter once per process. A failure is not cached,
// so a later Dial can succeed after the user turns Bluetooth on.
func defaultDevice() (ble.Device, error) {
	deviceMu.Lock()
	defer deviceMu.Unlock()
	if device != nil {
		return device, nil
	}
	d, err := newDevice()
	if err != nil {
		return nil, fmt.Errorf("%w%s", err, adapterHint)
	}
	device = d
	return device, nil
}

// Dial scans for a treadmill, connects to it and subscribes to status frames.
func Dial(ctx context.Context, opts Options) (*Transport, error) {
	if opts.Name == "" {
		opts.Name = protocol.DeviceName
	}
	if opts.ScanTimeout == 0 {
		opts.ScanTimeout = 25 * time.Second
	}
	if opts.ConnectTimeout == 0 {
		opts.ConnectTimeout = 15 * time.Second
	}
	dev, err := defaultDevice()
	if err != nil {
		return nil, fmt.Errorf("bluetooth: init adapter: %w", err)
	}

	adv, err := scan(ctx, dev, opts)
	if err != nil {
		return nil, err
	}
	connCtx, cancel := context.WithTimeout(ctx, opts.ConnectTimeout)
	defer cancel()
	client, err := dev.Dial(connCtx, adv.Addr())
	if err != nil {
		return nil, fmt.Errorf("bluetooth: connect: %w", err)
	}

	t := &Transport{
		client: client,
		addr:   adv.Addr().String(),
		name:   adv.LocalName(),
		frames: make(chan []byte, 64),
	}
	if err := t.setup(); err != nil {
		_ = client.CancelConnection()
		return nil, err
	}
	go func() {
		<-client.Disconnected()
		t.shutdown()
	}()
	return t, nil
}

func scan(ctx context.Context, dev ble.Device, opts Options) (ble.Advertisement, error) {
	scanCtx, cancel := context.WithTimeout(ctx, opts.ScanTimeout)
	defer cancel()

	var (
		mu    sync.Mutex
		found ble.Advertisement
	)
	err := dev.Scan(scanCtx, false, func(a ble.Advertisement) {
		if opts.Address != "" {
			if !strings.EqualFold(a.Addr().String(), opts.Address) {
				return
			}
		} else if !strings.Contains(strings.ToUpper(a.LocalName()), strings.ToUpper(opts.Name)) {
			return
		}
		mu.Lock()
		defer mu.Unlock()
		if found == nil {
			found = a
			cancel()
		}
	})
	mu.Lock()
	defer mu.Unlock()
	if found != nil {
		return found, nil
	}
	if ctx.Err() != nil {
		return nil, ctx.Err()
	}
	if err != nil && !errors.Is(err, context.Canceled) && !errors.Is(err, context.DeadlineExceeded) {
		return nil, fmt.Errorf("bluetooth: scan: %w", err)
	}
	return nil, fmt.Errorf("%w (name %q, address %q)", ErrNotFound, opts.Name, opts.Address)
}

func (t *Transport) setup() error {
	profile, err := t.client.DiscoverProfile(false)
	if err != nil {
		return fmt.Errorf("bluetooth: discover services: %w", err)
	}
	var notify *ble.Characteristic
	for _, s := range profile.Services {
		if shortUUID(s.UUID) != protocol.ServiceUUID {
			continue
		}
		for _, c := range s.Characteristics {
			switch shortUUID(c.UUID) {
			case protocol.NotifyCharacteristicUUID:
				notify = c
			case protocol.WriteCharacteristicUUID:
				t.write = c
			}
		}
	}
	if notify == nil || t.write == nil {
		return fmt.Errorf("bluetooth: service %s lacks characteristics %s/%s",
			protocol.ServiceUUID, protocol.NotifyCharacteristicUUID, protocol.WriteCharacteristicUUID)
	}
	if err := t.client.Subscribe(notify, false, t.onNotify); err != nil {
		return fmt.Errorf("bluetooth: subscribe: %w", err)
	}
	return nil
}

func (t *Transport) onNotify(b []byte) {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.closed {
		return
	}
	select {
	case t.frames <- append([]byte(nil), b...):
	default: // reader too slow; the next frame arrives within 1.3 s
	}
}

// shortUUID returns the 16-bit form of u, e.g. "fff0". go-ble stores UUIDs
// little-endian, so the 16-bit part of a 128-bit UUID sits at bytes 12–13.
func shortUUID(u ble.UUID) string {
	if len(u) == 16 {
		u = u[12:14]
	}
	return strings.ToLower(u.String())
}

// Write implements treadmill.Transport.
func (t *Transport) Write(ctx context.Context, frame []byte) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	t.mu.Lock()
	closed := t.closed
	t.mu.Unlock()
	if closed {
		return net.ErrClosed
	}
	return t.client.WriteCharacteristic(t.write, frame, false)
}

// Frames implements treadmill.Transport.
func (t *Transport) Frames() <-chan []byte { return t.frames }

// Close implements treadmill.Transport.
func (t *Transport) Close() error {
	err := t.client.CancelConnection()
	t.shutdown()
	return err
}

func (t *Transport) shutdown() {
	t.mu.Lock()
	defer t.mu.Unlock()
	if !t.closed {
		t.closed = true
		close(t.frames)
	}
}

// Address is the address of the connected treadmill.
func (t *Transport) Address() string { return t.addr }

// Name is the advertised name of the connected treadmill.
func (t *Transport) Name() string { return t.name }
