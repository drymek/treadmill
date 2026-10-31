package protocol

import (
	"fmt"
	"time"
)

// State is the treadmill state reported in every status frame.
type State uint8

const (
	// StateIdle: belt stopped, display shows "WELCOME" or the panel is used
	// to pick a program or a target.
	StateIdle State = 0x00
	// StateStarting: 3-second countdown after a start command.
	StateStarting State = 0x01
	// StateRunning: belt moving.
	StateRunning State = 0x02
	// StateStopping: belt braking after a stop command.
	StateStopping State = 0x04
	// StateStopped: belt halted; this frame still carries the final metrics.
	StateStopped State = 0x05
)

func (s State) String() string {
	switch s {
	case StateIdle:
		return "idle"
	case StateStarting:
		return "starting"
	case StateRunning:
		return "running"
	case StateStopping:
		return "stopping"
	case StateStopped:
		return "stopped"
	}
	return fmt.Sprintf("state(0x%02X)", uint8(s))
}

// Status is a decoded status notification.
//
// While the belt is idle and a program or target is being picked on the
// panel, the metric fields hold that target rather than a counter, and
// Incline holds the program number.
type Status struct {
	State State
	// Compact is true for the 7-byte frame sent while idle and right after a
	// start command. Compact frames carry no speed, incline or metrics.
	Compact bool

	TargetSpeed  Speed   // speed the belt is heading to; changes as soon as a command is accepted
	CurrentSpeed Speed   // actual belt speed; ramps towards TargetSpeed in about 2 s
	Incline      Incline // target incline; the treadmill does not report the motor position

	Elapsed   time.Duration // whole seconds since start
	Distance  int           // metres, 10 m resolution
	Calories  float64       // kcal, 0.1 kcal resolution
	HeartRate int           // beats per minute from the hand grip sensors, 0 without contact

	Raw []byte // the frame as received
}

// ParseStatus decodes a status notification, verifying framing, length and
// checksum.
func ParseStatus(frame []byte) (Status, error) {
	n := len(frame)
	if n < 4 || frame[0] != statusStart || frame[n-1] != statusEnd {
		return Status{}, fmt.Errorf("%w: bad status framing", ErrMalformed)
	}
	if int(frame[1]) != n-2 {
		return Status{}, fmt.Errorf("%w: status length byte %d, frame has %d bytes", ErrMalformed, frame[1], n)
	}
	if want := checksum(frame[1 : n-2]); frame[n-2] != want {
		return Status{}, fmt.Errorf("%w: status checksum %02X, want %02X", ErrMalformed, frame[n-2], want)
	}
	if frame[2] != statusCode {
		return Status{}, fmt.Errorf("%w: unknown status code %02X", ErrMalformed, frame[2])
	}

	st := Status{State: State(frame[3]), Raw: append([]byte(nil), frame...)}
	switch n {
	case CompactStatusLen:
		st.Compact = true
	case FullStatusLen:
		st.TargetSpeed = Speed(frame[4])
		st.CurrentSpeed = Speed(frame[5])
		st.Incline = Incline(frame[6])
		// frame[7] always mirrors frame[6].
		// frame[8] has always been 0x00; it may hold hours.
		st.Elapsed = time.Duration(frame[9])*time.Minute + time.Duration(frame[10])*time.Second
		st.Distance = int(be16(frame[11:13])) * 10
		st.Calories = float64(be16(frame[13:15])) / 10
		st.HeartRate = int(frame[15])
	default:
		return Status{}, fmt.Errorf("%w: unsupported status length %d", ErrMalformed, n)
	}
	return st, nil
}

func be16(b []byte) uint16 { return uint16(b[0])<<8 | uint16(b[1]) }
