//go:build integration

package bluetooth

import (
	"context"
	"os"
	"testing"
	"time"
)

const (
	envIntegrationEnabled = "TREADMILL_ITEST"
	envTarget             = "TREADMILL_BLE_TARGET"
)

func TestBleTreadmillIntegration_PhysicalDevice(t *testing.T) {
	if os.Getenv(envIntegrationEnabled) != "1" {
		t.Skip("set TREADMILL_ITEST=1 to enable physical treadmill integration tests")
	}

	target := os.Getenv(envTarget)
	if target == "" {
		t.Skip("set TREADMILL_BLE_TARGET to treadmill BLE name or address")
	}

	tm := NewBleTreadmill()

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()

	if err := tm.Connect(ctx, target); err != nil {
		t.Fatalf("Connect failed: %v", err)
	}

	defer func() {
		stopCtx, stopCancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer stopCancel()
		_ = tm.Send(stopCtx, Command{Stop: true})
		_ = tm.Close()
	}()

	t.Run("helpers", func(t *testing.T) {
		raw, err := DecodeHex("fb-03-a2-01-00-a6-fc")
		if err != nil {
			t.Fatalf("DecodeHex failed: %v", err)
		}
		if got := FormatHex(raw); got != "FB-03-A2-01-00-A6-FC" {
			t.Fatalf("FormatHex mismatch: %s", got)
		}
		if got := ClampSpeed(22.95); got != 22 {
			t.Fatalf("ClampSpeed mismatch: %v", got)
		}
		if got := ClampIncline(-5); got != 0 {
			t.Fatalf("ClampIncline mismatch: %v", got)
		}
	})

	t.Run("subscribe and last", func(t *testing.T) {
		n := waitForNotification(t, tm.Subscribe(), 12*time.Second)
		if n.RawHex == "" {
			t.Fatalf("notification missing RawHex")
		}
		last := tm.Last()
		if last.RawHex == "" {
			t.Fatalf("Last() returned empty notification")
		}
	})

	t.Run("send empty command", func(t *testing.T) {
		if err := tm.Send(ctx, Command{}); err != nil {
			t.Fatalf("Send(empty) failed: %v", err)
		}
	})

	t.Run("send raw frame", func(t *testing.T) {
		raw, err := DecodeHex("FB-03-A2-01-00-A6-FC")
		if err != nil {
			t.Fatalf("DecodeHex raw frame failed: %v", err)
		}
		if err := tm.SendRaw(ctx, raw); err != nil {
			t.Fatalf("SendRaw failed: %v", err)
		}
	})

	t.Run("start", func(t *testing.T) {
		if err := tm.Send(ctx, Command{Start: true}); err != nil {
			t.Fatalf("Send(start) failed: %v", err)
		}
		_ = waitForNotification(t, tm.Subscribe(), 12*time.Second)
	})

	t.Run("speed with release", func(t *testing.T) {
		speed := 1.5
		if err := tm.Send(ctx, Command{Speed: &speed, ReleaseAfter: true}); err != nil {
			t.Fatalf("Send(speed) failed: %v", err)
		}
		_ = waitForNotification(t, tm.Subscribe(), 12*time.Second)
	})

	t.Run("incline with release", func(t *testing.T) {
		incline := 1
		if err := tm.Send(ctx, Command{Incline: &incline, ReleaseAfter: true}); err != nil {
			t.Fatalf("Send(incline) failed: %v", err)
		}
		_ = waitForNotification(t, tm.Subscribe(), 12*time.Second)
	})

	t.Run("combined speed and incline", func(t *testing.T) {
		speed := 1.8
		incline := 2
		if err := tm.Send(ctx, Command{
			Speed:        &speed,
			Incline:      &incline,
			Master:       true,
			ReleaseAfter: true,
		}); err != nil {
			t.Fatalf("Send(speed+incline) failed: %v", err)
		}
		_ = waitForNotification(t, tm.Subscribe(), 12*time.Second)
	})

	t.Run("stop", func(t *testing.T) {
		if err := tm.Send(ctx, Command{Stop: true}); err != nil {
			t.Fatalf("Send(stop) failed: %v", err)
		}
		_ = waitForNotification(t, tm.Subscribe(), 12*time.Second)
	})
}

func waitForNotification(t *testing.T, ch <-chan Notification, timeout time.Duration) Notification {
	t.Helper()
	select {
	case n := <-ch:
		return n
	case <-time.After(timeout):
		t.Fatalf("timeout waiting for notification after %s", timeout)
		return Notification{}
	}
}
