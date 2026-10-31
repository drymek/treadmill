// Package protocol encodes commands for, and decodes status frames from,
// treadmills that advertise themselves over Bluetooth Low Energy as
// "RZ_TreadMil" (service FFF0).
//
// The package is pure: it performs no I/O and has no Bluetooth dependency,
// so it can be reused with any BLE stack. Every constant here was confirmed
// on a physical treadmill; the measurement log lives in docs/protocol.md in
// the repository root.
//
// # Wire format
//
// Commands (written to characteristic FFF2):
//
//	FB  LEN  GROUP  TYPE  MASTER  DATA...  CHK  FC
//
// LEN counts GROUP, TYPE, MASTER and DATA. CHK is the low byte of the sum of
// LEN through the last DATA byte.
//
// Status frames (notifications on characteristic FFF1):
//
//	FD  LEN  A1  PAYLOAD...  CHK  FE
//
// LEN counts every byte except FD and FE. CHK is the low byte of the sum of
// LEN through the last PAYLOAD byte. Two lengths exist: a 7-byte compact
// frame (idle, start acknowledgement) and an 18-byte full frame with metrics.
package protocol

import (
	"errors"
	"fmt"
)

// BLE identifiers of the treadmill.
const (
	// DeviceName is the local name the treadmill advertises.
	DeviceName = "RZ_TreadMil"
	// ServiceUUID is the 16-bit UUID of the only service that carries commands.
	ServiceUUID = "fff0"
	// NotifyCharacteristicUUID streams status frames (notify).
	NotifyCharacteristicUUID = "fff1"
	// WriteCharacteristicUUID accepts command frames (write).
	WriteCharacteristicUUID = "fff2"
)

// Frame delimiters.
const (
	commandStart byte = 0xFB
	commandEnd   byte = 0xFC
	statusStart  byte = 0xFD
	statusEnd    byte = 0xFE
)

// Command groups and types.
const (
	GroupSettings byte = 0xA1
	GroupControl  byte = 0xA2

	TypeSetSpeedIncline byte = 0x02 // in GroupSettings
	TypeStart           byte = 0x01 // in GroupControl
	TypeStop            byte = 0x04 // in GroupControl

	// MasterApp marks a command as coming from the app. The treadmill ignores
	// set-speed and stop commands sent with any other value.
	MasterApp byte = 0x01

	statusCode byte = 0xA1
)

// Frame lengths of status notifications.
const (
	CompactStatusLen = 7
	FullStatusLen    = 18
)

var (
	// ErrOutOfRange is returned for speed or incline values the treadmill
	// would silently ignore.
	ErrOutOfRange = errors.New("protocol: value out of range")
	// ErrMalformed is returned for frames with broken framing, length or checksum.
	ErrMalformed = errors.New("protocol: malformed frame")
)

// Speed is a belt speed in tenths of km/h, the unit used on the wire.
type Speed uint8

// Accepted target speed range. The treadmill ignores values outside it.
const (
	MinSpeed Speed = 10  // 1.0 km/h
	MaxSpeed Speed = 220 // 22.0 km/h
)

// SpeedFromKMH converts km/h to Speed, rounding to the nearest 0.1 km/h.
func SpeedFromKMH(kmh float64) (Speed, error) {
	tenths := kmh*10 + 0.5
	if !(tenths >= float64(MinSpeed) && tenths < float64(MaxSpeed)+1) {
		return 0, fmt.Errorf("%w: speed %.2f km/h (allowed %v–%v)", ErrOutOfRange, kmh, MinSpeed, MaxSpeed)
	}
	return Speed(tenths), nil
}

// KMH returns the speed in km/h.
func (s Speed) KMH() float64 { return float64(s) / 10 }

func (s Speed) String() string { return fmt.Sprintf("%d.%d km/h", s/10, s%10) }

// Incline is an incline level as shown on the treadmill display.
type Incline uint8

// Accepted incline range. The treadmill ignores values outside it.
const (
	MinIncline Incline = 0
	MaxIncline Incline = 22
)

// InclineFromLevel validates an incline level.
func InclineFromLevel(level int) (Incline, error) {
	if level < int(MinIncline) || level > int(MaxIncline) {
		return 0, fmt.Errorf("%w: incline %d (allowed %d–%d)", ErrOutOfRange, level, MinIncline, MaxIncline)
	}
	return Incline(level), nil
}

func checksum(b []byte) byte {
	var sum byte
	for _, v := range b {
		sum += v
	}
	return sum
}
