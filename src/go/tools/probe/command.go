package main

import (
	"encoding/hex"
	"errors"
	"fmt"
	"strconv"
	"strings"

	"craftersy.com/treadmill/src/go/lib/protocol"
)

const usage = `commands:
  start [m]                          A2 01 (m = master byte, default 1)
  stop [m]                           A2 04
  set <km/h> <incline> [m]           A1 02, no range check
  frame <group> <type> <m> [data…]   any command, hex bytes, checksum added
  raw <hex>                          bytes sent as is
  mark <text>                        note in the log, e.g. what the display shows`

// action is a parsed probe command: either a frame to write or a note to log.
type action struct {
	frame []byte
	note  string
}

// parseCommand turns one line of input into an action. The line itself is
// logged next to the frame, so the action carries no description.
func parseCommand(line string) (action, error) {
	fields := strings.Fields(line)
	if len(fields) == 0 {
		return action{}, errors.New("empty command")
	}
	args := fields[1:]
	switch strings.ToLower(fields[0]) {
	case "mark":
		note := strings.TrimSpace(strings.TrimPrefix(strings.TrimSpace(line), fields[0]))
		if note == "" {
			return action{}, errors.New("usage: mark <text>")
		}
		return action{note: note}, nil

	case "start", "stop":
		typ := protocol.TypeStart
		if strings.EqualFold(fields[0], "stop") {
			typ = protocol.TypeStop
		}
		m, err := optionalMaster(args, 0)
		if err != nil || len(args) > 1 {
			return action{}, fmt.Errorf("usage: %s [m]", fields[0])
		}
		return action{frame: protocol.EncodeCommand(protocol.GroupControl, typ, m)}, nil

	case "set":
		if len(args) < 2 || len(args) > 3 {
			return action{}, errors.New("usage: set <km/h> <incline> [m]")
		}
		kmh, err := strconv.ParseFloat(strings.ReplaceAll(args[0], ",", "."), 64)
		if err != nil || kmh < 0 || kmh*10 > 255.5 {
			return action{}, fmt.Errorf("speed %q must be 0–25.5 km/h", args[0])
		}
		incline, err := strconv.ParseUint(args[1], 10, 8)
		if err != nil {
			return action{}, fmt.Errorf("incline %q must be 0–255", args[1])
		}
		m, err := optionalMaster(args, 2)
		if err != nil {
			return action{}, err
		}
		speed := byte(kmh*10 + 0.5)
		return action{frame: protocol.EncodeCommand(protocol.GroupSettings, protocol.TypeSetSpeedIncline, m,
			speed, byte(incline), 0x00, 0x00)}, nil

	case "frame":
		if len(args) < 3 {
			return action{}, errors.New("usage: frame <group> <type> <m> [data…]")
		}
		b, err := hexBytes(args)
		if err != nil {
			return action{}, err
		}
		return action{frame: protocol.EncodeCommand(b[0], b[1], b[2], b[3:]...)}, nil

	case "raw":
		b, err := hexBytes([]string{strings.Join(args, "")})
		if err != nil || len(b) == 0 {
			return action{}, errors.New("usage: raw <hex>")
		}
		return action{frame: b}, nil
	}
	return action{}, fmt.Errorf("unknown command %q\n%s", fields[0], usage)
}

func optionalMaster(args []string, i int) (byte, error) {
	if len(args) <= i {
		return protocol.MasterApp, nil
	}
	v, err := strconv.ParseUint(strings.TrimPrefix(strings.ToLower(args[i]), "0x"), 16, 8)
	if err != nil {
		return 0, fmt.Errorf("master byte %q must be one hex byte", args[i])
	}
	return byte(v), nil
}

// hexBytes decodes hex written as "A1", "0xA1", "FB-03-A2" or "FB:03".
func hexBytes(args []string) ([]byte, error) {
	clean := strings.NewReplacer("0x", "", "0X", "", "-", "", ":", "").Replace(strings.Join(args, ""))
	b, err := hex.DecodeString(clean)
	if err != nil {
		return nil, fmt.Errorf("bad hex %q: %w", strings.Join(args, " "), err)
	}
	return b, nil
}
