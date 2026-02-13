package main

import (
	"context"
	"fmt"
	"log"
	"runtime"
	"time"

	"github.com/go-ble/ble"
	"github.com/go-ble/ble/darwin"
)

func main() {
	if runtime.GOOS != "darwin" {
		log.Fatal("Ten skaner jest pod macOS (darwin).")
	}
	d, err := darwin.NewDevice()
	if err != nil {
		log.Fatalf("BLE init error: %v", err)
	}
	ble.SetDefaultDevice(d)

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	fmt.Println("Skanuję 30s i wypisuję reklamy BLE...")

	seen := map[string]bool{}
	err = ble.Scan(ctx, true, func(a ble.Advertisement) {
		key := a.Addr().String()
		if seen[key] {
			return
		}
		seen[key] = true

		fmt.Printf("addr=%s rssi=%d name=%q services=%v\n",
			a.Addr(), a.RSSI(), a.LocalName(), a.Services())
	}, nil)

	if err != nil && err != context.DeadlineExceeded {
		log.Fatalf("Scan error: %v", err)
	}
}

