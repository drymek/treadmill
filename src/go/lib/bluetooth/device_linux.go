package bluetooth

import (
	"github.com/go-ble/ble"
	"github.com/go-ble/ble/linux"
)

// adapterHint explains the most common failure: no raw HCI socket access.
const adapterHint = " (on Linux run as root or grant cap_net_raw,cap_net_admin to the binary, and make sure " +
	"the adapter is up: hciconfig hci0 up)"

func newDevice() (ble.Device, error) { return linux.NewDevice() }
