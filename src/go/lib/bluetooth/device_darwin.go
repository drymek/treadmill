package bluetooth

import (
	"github.com/go-ble/ble"
	"github.com/go-ble/ble/darwin"
)

// adapterHint explains the most common failure: macOS reports the adapter
// state as "unknown" when the calling app lacks Bluetooth permission.
const adapterHint = " (on macOS the app running this program, e.g. Terminal or iTerm, needs permission in " +
	"System Settings → Privacy & Security → Bluetooth; tools without a permission prompt are blocked silently)"

func newDevice() (ble.Device, error) { return darwin.NewDevice() }
