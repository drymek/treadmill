package protocol

import "fmt"

// Command is a decoded command frame.
type Command struct {
	Group  byte
	Type   byte
	Master byte
	Data   []byte
}

// EncodeCommand builds a command frame from its parts. It is the low-level
// building block behind Start, Stop and SetSpeedIncline and is useful for
// protocol research. It panics if data is longer than 252 bytes.
func EncodeCommand(group, typ, master byte, data ...byte) []byte {
	if len(data) > 252 {
		panic("protocol: command data too long")
	}
	frame := make([]byte, 0, 7+len(data))
	frame = append(frame, commandStart, byte(3+len(data)), group, typ, master)
	frame = append(frame, data...)
	frame = append(frame, checksum(frame[1:]), commandEnd)
	return frame
}

// Encode returns the wire form of c.
func (c Command) Encode() []byte { return EncodeCommand(c.Group, c.Type, c.Master, c.Data...) }

// DecodeCommand parses a command frame, verifying framing, length and checksum.
func DecodeCommand(frame []byte) (Command, error) {
	n := len(frame)
	if n < 7 || frame[0] != commandStart || frame[n-1] != commandEnd {
		return Command{}, fmt.Errorf("%w: bad command framing", ErrMalformed)
	}
	if int(frame[1]) != n-4 {
		return Command{}, fmt.Errorf("%w: command length byte %d, frame has %d bytes", ErrMalformed, frame[1], n)
	}
	if want := checksum(frame[1 : n-2]); frame[n-2] != want {
		return Command{}, fmt.Errorf("%w: command checksum %02X, want %02X", ErrMalformed, frame[n-2], want)
	}
	return Command{
		Group:  frame[2],
		Type:   frame[3],
		Master: frame[4],
		Data:   append([]byte(nil), frame[5:n-2]...),
	}, nil
}

// Start returns the command that starts the belt: a 3-second countdown,
// then 1.0 km/h. A new start resets the elapsed time, distance and calories.
func Start() []byte { return EncodeCommand(GroupControl, TypeStart, MasterApp) }

// Stop returns the command that brakes the belt to a halt.
func Stop() []byte { return EncodeCommand(GroupControl, TypeStop, MasterApp) }

// SetSpeedIncline returns the command that sets the target speed and incline.
//
// Both values always apply: there is no way to change only one of them, so
// callers that want to keep the incline must pass the current one.
func SetSpeedIncline(speed Speed, incline Incline) ([]byte, error) {
	if speed < MinSpeed || speed > MaxSpeed {
		return nil, fmt.Errorf("%w: speed %v", ErrOutOfRange, speed)
	}
	if incline > MaxIncline {
		return nil, fmt.Errorf("%w: incline %d", ErrOutOfRange, incline)
	}
	return EncodeCommand(GroupSettings, TypeSetSpeedIncline, MasterApp, byte(speed), byte(incline), 0x00, 0x00), nil
}
