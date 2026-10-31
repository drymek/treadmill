package bluetooth

import (
	"testing"

	"github.com/go-ble/ble"
)

func TestShortUUID(t *testing.T) {
	for in, want := range map[string]string{
		"fff0":                                 "fff0",
		"FFF1":                                 "fff1",
		"0000fff2-0000-1000-8000-00805f9b34fb": "fff2",
	} {
		u, err := ble.Parse(in)
		if err != nil {
			t.Fatal(err)
		}
		if got := shortUUID(u); got != want {
			t.Errorf("shortUUID(%s) = %s, want %s", in, got, want)
		}
	}
}
