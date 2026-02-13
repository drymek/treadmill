package bluetooth

import (
	"context"
	"encoding/hex"
	"errors"
	"fmt"
	"log"
	"math"
	"runtime"
	"strings"
	"sync"
	"time"

	"github.com/go-ble/ble"
	"github.com/go-ble/ble/darwin"
	"github.com/go-ble/ble/linux"
)

// Command opisuje polecenie wysyłane do bieżni.
type Command struct {
	Start   bool
	Stop    bool
	Speed   *float64 // km/h 0.0..22.0 step 0.1
	Incline *int     // 0..22

	// Na tej bieżni: Speed/Incline działa tylko jako MASTER (01).
	Master bool

	// Jeśli true i wysyłamy MASTER speed/incline => po tym leci SLAVE START.
	ReleaseAfter bool
}

// Notification to sparsowany status otrzymany z FFF1 (notify).
type Notification struct {
	RawHex string

	State   int
	Speed   float64
	Incline int

	TimeSeconds   int
	DistanceUnits int
	CaloriesUnits int
}

// Treadmill to minimalny kontrakt na komunikację.
type Treadmill interface {
	Connect(ctx context.Context, nameOrAddr string) error
	Send(ctx context.Context, cmd Command) error
	SendRaw(ctx context.Context, raw []byte) error
	Subscribe() <-chan Notification
	Last() Notification
	Close() error
}

// BleTreadmill implementuje komunikację BLE z urządzeniem.
type BleTreadmill struct {
	cln     ble.Client
	chWrite *ble.Characteristic
	chNotif *ble.Characteristic

	notifCh chan Notification

	mu        sync.RWMutex
	lastNotif Notification

	writeMu sync.Mutex
}

func NewBleTreadmill() *BleTreadmill {
	return &BleTreadmill{notifCh: make(chan Notification, 100)}
}

func (t *BleTreadmill) Subscribe() <-chan Notification { return t.notifCh }

func (t *BleTreadmill) Last() Notification {
	t.mu.RLock()
	defer t.mu.RUnlock()
	return t.lastNotif
}

func (t *BleTreadmill) Close() error {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.cln != nil {
		t.cln.CancelConnection()
		t.cln = nil
	}
	return nil
}

func initDevice() (ble.Device, error) {
	switch runtime.GOOS {
	case "darwin":
		return darwin.NewDevice()
	case "linux":
		return linux.NewDevice()
	default:
		return nil, fmt.Errorf("unsupported OS: %s (ten skrypt wspiera macOS i Linux)", runtime.GOOS)
	}
}

func uuidShort(u ble.UUID) string {
	s := strings.ToLower(u.String())
	if len(s) >= 4 {
		return s[len(s)-4:]
	}
	return s
}

// FormatHex wypisuje bajty jako XX-YY-ZZ.
func FormatHex(b []byte) string {
	out := strings.ToUpper(hex.EncodeToString(b))
	var parts []string
	for i := 0; i < len(out); i += 2 {
		parts = append(parts, out[i:i+2])
	}
	return strings.Join(parts, "-")
}

// DecodeHex zamienia zapis HEX (spacje, -, :, 0x) na bajty.
func DecodeHex(s string) ([]byte, error) {
	clean := strings.NewReplacer(" ", "", "-", "", ":", "", "0x", "", "0X", "").Replace(s)
	if len(clean)%2 != 0 {
		return nil, fmt.Errorf("hex ma nieparzystą długość: %d", len(clean))
	}
	return hex.DecodeString(clean)
}

// buildTxFB buduje ramkę: FB <len> <code> <type> <master> <datas...> <chk> FC
func buildTxFB(code, typ, master byte, datas ...byte) []byte {
	l := byte(3 + len(datas))
	frame := make([]byte, 0, 2+int(l)+1)
	frame = append(frame, 0xFB, l, code, typ, master)
	frame = append(frame, datas...)

	var sum int
	sum += int(l) + int(code) + int(typ) + int(master)
	for _, d := range datas {
		sum += int(d)
	}
	chk := byte(sum & 0xFF)
	frame = append(frame, chk, 0xFC)
	return frame
}

func frameStart(master bool) []byte {
	m := byte(0x00)
	if master {
		m = 0x01
	}
	return buildTxFB(0xA2, 0x01, m)
}

func frameStop(master bool) []byte {
	m := byte(0x00)
	if master {
		m = 0x01
	}
	return buildTxFB(0xA2, 0x04, m)
}

func frameSetSpeedIncline(master bool, speed10 byte, incline byte) []byte {
	m := byte(0x00)
	if master {
		m = 0x01
	}
	return buildTxFB(0xA1, 0x02, m, speed10, incline, 0x00, 0x00)
}

func parseNotify(b []byte) (Notification, bool) {
	if len(b) < 6 {
		return Notification{}, false
	}
	if b[0] != 0xFD || b[len(b)-1] != 0xFE {
		return Notification{}, false
	}
	if b[2] != 0xA1 {
		return Notification{}, false
	}

	n := Notification{
		RawHex: FormatHex(b),
		State:  int(b[3]),
		Speed:  float64(b[4]) / 10.0,
	}
	if len(b) >= 8 {
		n.Incline = int(b[6])
	}
	if len(b) >= 18 {
		n.TimeSeconds = int(uint16(b[10]) | (uint16(b[11]) << 8))
		n.DistanceUnits = int(uint16(b[12]) | (uint16(b[13]) << 8))
		n.CaloriesUnits = int(uint16(b[14]) | (uint16(b[15]) << 8))
	}
	return n, true
}

func (t *BleTreadmill) Connect(ctx context.Context, nameOrAddr string) error {
	d, err := initDevice()
	if err != nil {
		return err
	}
	ble.SetDefaultDevice(d)

	isAddr := isProbablyMacAddr(nameOrAddr)
	scanCtx, cancelScan := context.WithTimeout(ctx, 25*time.Second)
	defer cancelScan()

	var found ble.Advertisement
	log.Println("Skanuję BLE...")

	err = ble.Scan(scanCtx, false, func(a ble.Advertisement) {
		if isAddr && strings.EqualFold(a.Addr().String(), nameOrAddr) {
			found = a
			cancelScan()
			return
		}
		if !isAddr {
			n := a.LocalName()
			if n != "" && strings.Contains(strings.ToUpper(n), strings.ToUpper(nameOrAddr)) {
				found = a
				cancelScan()
				return
			}
		}
	}, nil)
	if err != nil && !errors.Is(err, context.Canceled) && !errors.Is(err, context.DeadlineExceeded) {
		return fmt.Errorf("scan error: %w", err)
	}
	if found == nil {
		return fmt.Errorf("nie znaleziono bieżni (nameOrAddr=%q)", nameOrAddr)
	}

	log.Printf("Znalezione: name=%q addr=%s rssi=%d", found.LocalName(), found.Addr(), found.RSSI())

	connCtx, cancelConn := context.WithTimeout(ctx, 15*time.Second)
	defer cancelConn()

	cln, err := ble.Dial(connCtx, found.Addr())
	if err != nil {
		return fmt.Errorf("dial error: %w", err)
	}

	p, err := cln.DiscoverProfile(false)
	if err != nil {
		cln.CancelConnection()
		return fmt.Errorf("discover profile error: %w", err)
	}

	var chNotify *ble.Characteristic
	var chWrite *ble.Characteristic
	for _, s := range p.Services {
		if uuidShort(s.UUID) != "fff0" {
			continue
		}
		for _, c := range s.Characteristics {
			switch uuidShort(c.UUID) {
			case "fff1":
				chNotify = c
			case "fff2":
				chWrite = c
			}
		}
	}
	if chNotify == nil || chWrite == nil {
		cln.CancelConnection()
		return fmt.Errorf("nie znaleziono FFF1/FFF2 w FFF0 (notify=%v write=%v)", chNotify != nil, chWrite != nil)
	}

	if err := cln.Subscribe(chNotify, false, func(b []byte) {
		if n, ok := parseNotify(b); ok {
			t.mu.Lock()
			t.lastNotif = n
			t.mu.Unlock()

			select {
			case t.notifCh <- n:
			default:
			}
		}
	}); err != nil {
		cln.CancelConnection()
		return fmt.Errorf("subscribe error: %w", err)
	}

	t.mu.Lock()
	t.cln = cln
	t.chNotif = chNotify
	t.chWrite = chWrite
	t.mu.Unlock()

	log.Println("Połączono i subskrypcja NOTIFY włączona.")
	return nil
}

func isProbablyMacAddr(s string) bool {
	clean := strings.ToLower(strings.TrimSpace(s))
	if len(clean) != 32 {
		return false
	}
	for _, r := range clean {
		if (r < '0' || r > '9') && (r < 'a' || r > 'f') {
			return false
		}
	}
	return true
}

func (t *BleTreadmill) SendRaw(ctx context.Context, raw []byte) error {
	t.writeMu.Lock()
	defer t.writeMu.Unlock()

	t.mu.RLock()
	cln := t.cln
	ch := t.chWrite
	t.mu.RUnlock()

	if cln == nil || ch == nil {
		return fmt.Errorf("niepołączono")
	}

	_ = ctx
	if err := cln.WriteCharacteristic(ch, raw, false); err != nil {
		return err
	}
	return nil
}

func ClampSpeed(v float64) float64 {
	if v < 0 {
		return 0
	}
	if v > 22 {
		return 22
	}
	return math.Round(v*10.0) / 10.0
}

func ClampIncline(v int) int {
	if v < 0 {
		return 0
	}
	if v > 22 {
		return 22
	}
	return v
}

func (t *BleTreadmill) Send(ctx context.Context, cmd Command) error {
	if cmd.Start {
		raw := frameStart(true)
		log.Printf("TX START (MASTER): %s", FormatHex(raw))
		return t.SendRaw(ctx, raw)
	}
	if cmd.Stop {
		raw := frameStop(true)
		log.Printf("TX STOP (MASTER): %s", FormatHex(raw))
		return t.SendRaw(ctx, raw)
	}

	if cmd.Speed == nil && cmd.Incline == nil {
		return nil
	}

	t.mu.RLock()
	last := t.lastNotif
	t.mu.RUnlock()

	speed := last.Speed
	incline := last.Incline

	if cmd.Speed != nil {
		speed = ClampSpeed(*cmd.Speed)
	}
	if cmd.Incline != nil {
		incline = ClampIncline(*cmd.Incline)
	}

	speed10 := byte(math.Round(speed * 10.0))
	incB := byte(incline)

	master := cmd.Master
	if cmd.Speed != nil || cmd.Incline != nil {
		master = true
	}

	raw := frameSetSpeedIncline(master, speed10, incB)
	log.Printf("TX SET (master=%v speed=%.1f incline=%d): %s", master, speed, incline, FormatHex(raw))

	if err := t.SendRaw(ctx, raw); err != nil {
		return err
	}

	if master && cmd.ReleaseAfter {
		release := frameStart(false)
		log.Printf("TX RELEASE (SLAVE START): %s", FormatHex(release))
		_ = t.SendRaw(ctx, release)
	}

	return nil
}
