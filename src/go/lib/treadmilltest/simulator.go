// Package treadmilltest provides an in-memory treadmill simulator that
// implements treadmill.Transport, so applications can be developed and tested
// without the hardware.
//
// The simulator reproduces the behaviour measured on the real device:
// a compact status frame while idle, a countdown after start, the belt
// ramping towards the target speed, braking after stop, out-of-range values
// being ignored and commands without the master flag being ignored (except
// start). It does not simulate calories.
package treadmilltest

import (
	"context"
	"errors"
	"sync"
	"time"

	"craftersy.com/treadmill/src/go/lib/protocol"
)

// ErrClosed is returned by Write after Close.
var ErrClosed = errors.New("treadmilltest: simulator closed")

// Ramp rates per tick, in tenths of km/h, as measured on the device.
const (
	accelerationPerTick = 5
	brakingPerTick      = 3
	countdownTicks      = 3
)

// Simulator is a simulated treadmill. Each tick it advances the belt by one
// simulated second and emits a status frame.
type Simulator struct {
	mu        sync.Mutex
	state     protocol.State
	target    protocol.Speed
	current   protocol.Speed
	incline   protocol.Incline
	countdown int
	seconds   int
	metres    float64
	heartRate int
	writes    [][]byte
	closed    bool

	frames chan []byte
	stop   chan struct{}
	wg     sync.WaitGroup
}

// NewSimulator starts a simulator that emits a status frame every tick. The
// real treadmill ticks every ~1.3 s; tests usually pass a few milliseconds.
func NewSimulator(tick time.Duration) *Simulator {
	s := &Simulator{
		frames: make(chan []byte, 64),
		stop:   make(chan struct{}),
	}
	s.wg.Add(1)
	go func() {
		defer s.wg.Done()
		t := time.NewTicker(tick)
		defer t.Stop()
		for {
			select {
			case <-s.stop:
				return
			case <-t.C:
				s.mu.Lock()
				s.advance()
				s.emit()
				s.mu.Unlock()
			}
		}
	}()
	return s
}

// Frames implements treadmill.Transport.
func (s *Simulator) Frames() <-chan []byte { return s.frames }

// Close implements treadmill.Transport.
func (s *Simulator) Close() error {
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return nil
	}
	s.closed = true
	close(s.stop)
	s.mu.Unlock()
	s.wg.Wait()
	s.mu.Lock()
	close(s.frames)
	s.mu.Unlock()
	return nil
}

// Write implements treadmill.Transport.
func (s *Simulator) Write(_ context.Context, frame []byte) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return ErrClosed
	}
	s.writes = append(s.writes, append([]byte(nil), frame...))

	cmd, err := protocol.DecodeCommand(frame)
	if err != nil {
		return nil // the device silently drops garbage
	}
	switch {
	case cmd.Group == protocol.GroupControl && cmd.Type == protocol.TypeStart:
		if s.state == protocol.StateIdle || s.state == protocol.StateStopped {
			s.state, s.countdown = protocol.StateStarting, countdownTicks
			s.target, s.current, s.incline = 0, 0, 0
			s.seconds, s.metres = 0, 0
			s.push([]byte{0xFD, 0x05, 0xA1, byte(protocol.StateStarting), 0x03})
		}
	case cmd.Group == protocol.GroupControl && cmd.Type == protocol.TypeStop && cmd.Master == protocol.MasterApp:
		switch s.state {
		case protocol.StateStarting, protocol.StateRunning:
			s.state = protocol.StateStopping
			s.target = 0
		case protocol.StateIdle:
			s.state = protocol.StateStopped
		}
		s.emit()
	case cmd.Group == protocol.GroupSettings:
		if cmd.Type == protocol.TypeSetSpeedIncline && cmd.Master == protocol.MasterApp &&
			len(cmd.Data) == 4 && s.state == protocol.StateRunning {
			speed, incline := protocol.Speed(cmd.Data[0]), protocol.Incline(cmd.Data[1])
			if speed >= protocol.MinSpeed && speed <= protocol.MaxSpeed && incline <= protocol.MaxIncline {
				s.target, s.incline = speed, incline
			}
		}
		s.emit() // the device answers every settings command, even ignored ones
	}
	return nil
}

// Writes returns every frame written so far.
func (s *Simulator) Writes() [][]byte {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([][]byte(nil), s.writes...)
}

// SetHeartRate simulates hands on the grip sensors; 0 means no contact.
func (s *Simulator) SetHeartRate(bpm int) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.heartRate = bpm
}

// advance moves the simulation one second forward. Caller holds s.mu.
func (s *Simulator) advance() {
	switch s.state {
	case protocol.StateStopped:
		s.state = protocol.StateIdle
	case protocol.StateStarting:
		s.countdown--
		s.target, s.current = protocol.MinSpeed, protocol.MinSpeed/2
		if s.countdown <= 0 {
			s.state = protocol.StateRunning
		}
	case protocol.StateRunning:
		s.seconds++
		s.current = approach(s.current, s.target, accelerationPerTick)
		s.metres += s.current.KMH() / 3.6
	case protocol.StateStopping:
		s.current = approach(s.current, 0, brakingPerTick)
		s.target, s.incline = s.current, 0
		if s.current == 0 {
			s.state = protocol.StateStopped
		}
	}
}

func approach(from, to protocol.Speed, step int) protocol.Speed {
	d := int(to) - int(from)
	switch {
	case d > step:
		d = step
	case d < -step:
		d = -step
	}
	return protocol.Speed(int(from) + d)
}

// emit queues the current status. Caller holds s.mu.
func (s *Simulator) emit() {
	if s.state == protocol.StateIdle {
		s.push([]byte{0xFD, 0x05, 0xA1, byte(protocol.StateIdle), 0x00})
		return
	}
	dist := int(s.metres) / 10
	s.push([]byte{
		0xFD, 0x10, 0xA1, byte(s.state),
		byte(s.target), byte(s.current), byte(s.incline), byte(s.incline),
		0x00, byte(s.seconds / 60), byte(s.seconds % 60),
		byte(dist >> 8), byte(dist),
		0x00, 0x00, // calories are not simulated
		byte(s.heartRate),
	})
}

// push adds checksum and end byte to a frame body and queues it, dropping
// it when nobody reads, like a radio would. Caller holds s.mu.
func (s *Simulator) push(body []byte) {
	var sum byte
	for _, b := range body[1:] {
		sum += b
	}
	frame := append(body, sum, 0xFE)
	select {
	case s.frames <- frame:
	default:
	}
}
