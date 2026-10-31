//go:build !darwin && !linux

package bluetooth

import (
	"fmt"
	"runtime"

	"github.com/go-ble/ble"
)

const adapterHint = ""

func newDevice() (ble.Device, error) {
	return nil, fmt.Errorf("unsupported platform %s (macOS and Linux only)", runtime.GOOS)
}
