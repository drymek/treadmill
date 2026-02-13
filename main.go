//package main
//
//import (
//	"context"
//	"encoding/hex"
//	"flag"
//	"fmt"
//	"log"
//	"os"
//	"os/signal"
//	"runtime"
//	"strings"
//	"syscall"
//	"time"
//
//	"github.com/go-ble/ble"
//	"github.com/go-ble/ble/darwin"
//	"github.com/go-ble/ble/linux"
//)
//
//// 16-bit UUIDs expanded to 128-bit:
//var (
//	uServiceFFF0 = ble.MustParse("0000fff0-0000-1000-8000-00805f9b34fb")
//	uCharFFF1    = ble.MustParse("0000fff1-0000-1000-8000-00805f9b34fb") // NOTIFY (status)
//	uCharFFF2    = ble.MustParse("0000fff2-0000-1000-8000-00805f9b34fb") // WRITE  (commands)
//)
//
//type Status struct {
//	Raw       []byte
//	SpeedKmh  float64
//	SpeedByte byte
//	TypeByte  byte
//	StateByte byte
//}
//
//func parseStatus(b []byte) Status {
//	st := Status{Raw: append([]byte(nil), b...)}
//	if len(b) >= 6 {
//		st.TypeByte = b[2]
//		st.StateByte = b[3]
//		st.SpeedByte = b[4]
//		st.SpeedKmh = float64(b[4]) / 10.0 // potwierdzone u Ciebie: 0x0A=1.0, 0x1E=3.0, 0x3C=6.0
//	}
//	return st
//}
//
//func uuidShort(u ble.UUID) string {
//	s := strings.ToLower(u.String())
//	// na macOS może być "fff0", na innych "0000fff0-..."
//	if len(s) >= 4 {
//		return s[len(s)-4:]
//	}
//	return s
//}
//
//func fmtHex(b []byte) string {
//	out := strings.ToUpper(hex.EncodeToString(b))
//	var parts []string
//	for i := 0; i < len(out); i += 2 {
//		parts = append(parts, out[i:i+2])
//	}
//	return strings.Join(parts, "-")
//}
//
//func decodeHex(s string) ([]byte, error) {
//	clean := strings.NewReplacer(" ", "", "-", "", ":", "", "0x", "", "0X", "").Replace(s)
//	if len(clean)%2 != 0 {
//		return nil, fmt.Errorf("hex ma nieparzystą długość: %d", len(clean))
//	}
//	return hex.DecodeString(clean)
//}
//
//func initDevice() (ble.Device, error) {
//	switch runtime.GOOS {
//	case "darwin":
//		return darwin.NewDevice()
//	case "linux":
//		return linux.NewDevice()
//	default:
//		return nil, fmt.Errorf("unsupported OS: %s (ten skrypt wspiera macOS i Linux)", runtime.GOOS)
//	}
//}
//
//func canWriteNoResp(c *ble.Characteristic) bool {
//	// W go-ble: WriteNoResp to zwykle 0x04, Write to 0x08
//	// ale nie zakładamy — sprawdzimy bitowo na typie ble.Property.
//	const writeNoResp ble.Property = 0x04
//	return c.Property&writeNoResp != 0
//}
//
//func buildSetSpeedFromLast(speedKmh float64, op *byte) ([]byte, error) {
//	if len(lastStatus) != 18 {
//		return nil, fmt.Errorf("brak lastStatus (18B) – poczekaj aż poleci FD-10-A1-...")
//	}
//
//	target := byte(speedKmh * 10.0)
//
//	cmd := append([]byte(nil), lastStatus...) // kopia
//	if op != nil {
//		cmd[2] = *op
//	} else {
//		cmd[2] = 0xA0 // domyślnie (ale teraz będziemy nadpisywać)
//	}
//	// command (zmiana tylko typu)
//	cmd[4] = target // target speed
//	// cmd[5] zostawiamy jak w statusie (current) – niech bieżnia sama rampuje
//	cmd[16] = 0
//	cmd[16] = chkSum(cmd)
//	return cmd, nil
//}
//
//var pendingSpeed float64 = -1
//var sentSpeed bool
//
//func buildTxFB(code, typ, master byte, datas ...byte) []byte {
//	// len = 3 + len(datas)  (code + type + master + datas + chk)
//	l := byte(3 + len(datas))
//
//	frame := make([]byte, 0, 2+int(l)+1)
//	frame = append(frame, 0xFB, l, code, typ, master)
//	frame = append(frame, datas...)
//
//	// checksum = (l + code + typ + master + sum(datas)) & 0xFF
//	var sum int
//	sum += int(l) + int(code) + int(typ) + int(master)
//	for _, d := range datas {
//		sum += int(d)
//	}
//	chk := byte(sum & 0xFF)
//
//	frame = append(frame, chk, 0xFC)
//	return frame
//}
//
//func main() {
//	var (
//		addr    = flag.String("addr", "", "Adres z go-ble na macOS (np. 31c1de4510f8123f337472368e4311fa).")
//		name    = flag.String("name", "RZ_TreadMil", "Nazwa urządzenia (dopasowanie zawiera).")
//		timeout = flag.Duration("timeout", 25*time.Second, "Timeout skanowania/łączenia.")
//		sendHex = flag.String("send", "", "Wyślij ramkę HEX na FFF2. Opcjonalne.")
//		speed   = flag.Float64("speed", -1, "Ustaw prędkość (km/h), np. 3.0")
//		start   = flag.Bool("start", false, "Wyślij komendę START")
//	)
//	op := flag.Int("op", -1, "Opcode dla komendy (bajt [2]), np. 160 dla 0xA0, 162 dla 0xA2")
//
//	flag.Parse()
//	pendingSpeed = *speed
//
//	var forcedOp *byte
//	if *op >= 0 {
//		b := byte(*op)
//		forcedOp = &b
//	}
//	_ = forcedOp
//
//	d, err := initDevice()
//	if err != nil {
//		log.Fatalf("BLE init error: %v", err)
//	}
//	ble.SetDefaultDevice(d)
//
//	ctx, cancel := context.WithTimeout(context.Background(), *timeout)
//	defer cancel()
//
//	fmt.Println("Skanuję BLE...")
//
//	var found ble.Advertisement
//	err = ble.Scan(ctx, false, func(a ble.Advertisement) {
//		// 1) preferuj addr jeśli podany
//		if *addr != "" && strings.EqualFold(a.Addr().String(), *addr) {
//			found = a
//			cancel()
//			return
//		}
//
//		// 2) fallback: po nazwie
//		n := a.LocalName()
//		if n != "" && strings.Contains(strings.ToUpper(n), strings.ToUpper(*name)) {
//			found = a
//			cancel()
//			return
//		}
//	}, nil)
//
//	if err != nil && err != context.Canceled && err != context.DeadlineExceeded {
//		log.Fatalf("Scan error: %v", err)
//	}
//	if found == nil {
//		log.Fatalf("Nie znaleziono urządzenia po nazwie ~ %q. Sprawdź uprawnienia Bluetooth dla Terminal/iTerm.", *name)
//	}
//
//	fmt.Printf("Znalezione: name=%q addr=%s rssi=%d\n", found.LocalName(), found.Addr(), found.RSSI())
//
//	ctxConn, cancelConn := context.WithTimeout(context.Background(), 15*time.Second)
//	defer cancelConn()
//
//	cln, err := ble.Dial(ctxConn, found.Addr())
//	if err != nil {
//		log.Fatalf("Connect error: %v", err)
//	}
//	defer cln.CancelConnection()
//	fmt.Println("Połączono.")
//
//	//p, err := cln.DiscoverProfile(true)
//	p, err := cln.DiscoverProfile(false) // <— ZMIANA: false
//	if err != nil {
//		log.Fatalf("DiscoverProfile error: %v", err)
//	}
//
//	fmt.Println("=== SERVICES / CHARS DISCOVERED ===")
//	for _, s := range p.Services {
//		fmt.Printf("SERVICE %s\n", s.UUID)
//		for _, c := range s.Characteristics {
//			fmt.Printf("  CHAR %s props=%v\n", c.UUID, c.Property)
//		}
//	}
//	fmt.Println("=== END ===")
//	if err != nil {
//		log.Fatalf("DiscoverProfile error: %v", err)
//	}
//
//	var chNotify *ble.Characteristic
//	var chWrite *ble.Characteristic
//
//	for _, s := range p.Services {
//		if uuidShort(s.UUID) != "fff0" {
//			continue
//		}
//		for _, c := range s.Characteristics {
//			switch uuidShort(c.UUID) {
//			case "fff1":
//				chNotify = c
//			case "fff2":
//				chWrite = c
//			}
//		}
//	}
//
//	if chNotify == nil || chWrite == nil {
//		log.Fatalf("Nie znaleziono FFF1/FFF2 w usłudze FFF0. notify=%v write=%v", chNotify != nil, chWrite != nil)
//	}
//
//	fmt.Println("Włączam NOTIFY na FFF1...")
//	err = cln.Subscribe(chNotify, false, func(b []byte) {
//		st := parseStatus(b)
//
//		if len(b) >= 7 && b[0] == 0xFD && b[len(b)-1] == 0xFE {
//			want := b[len(b)-2]
//			// zbuduj kopię ramki z "0" w miejscu checksum, żeby liczyć konsekwentnie
//			tmp := append([]byte(nil), b...)
//			tmp[len(tmp)-2] = 0
//
//			fmt.Printf("CHK want=%02X sum=%02X xor=%02X sumFD=%02X\n",
//				want, chkSum(tmp), chkXor(tmp), chkSumWithFD(tmp))
//		}
//
//		if len(b) == 18 && b[0] == 0xFD && b[1] == 0x10 && b[2] == 0xA1 && b[17] == 0xFE {
//			fmt.Printf("DBG 18B: pendingSpeed=%.1f sentSpeed=%v\n", pendingSpeed, sentSpeed)
//
//			lastStatus = append([]byte(nil), b...)
//
//			//if pendingSpeed >= 0 && !sentSpeed {
//			//	raw := append([]byte(nil), lastStatus...)
//			//	fmt.Printf("AUTO TEST write lastStatus: %s\n", fmtHex(raw))
//			//	if err := cln.WriteCharacteristic(chWrite, raw, false); err != nil {
//			//		log.Printf("WriteCharacteristic error: %v", err)
//			//	} else {
//			//		fmt.Println("AUTO TEST sent.")
//			//		sentSpeed = true
//			//	}
//			//}
//		}
//
//		fmt.Printf("NOTIFY len=%d hex=%s | type=0x%02X state=0x%02X speed=%.1f km/h\n",
//			len(b), fmtHex(b), st.TypeByte, st.StateByte, st.SpeedKmh)
//	})
//	if err != nil {
//		log.Fatalf("Subscribe error: %v", err)
//	}
//
//	//if *speed >= 0 {
//	//	raw, err := buildSetSpeedFromLast(*speed)
//	//	if err != nil {
//	//		log.Fatalf("build cmd error: %v", err)
//	//	}
//	//	fmt.Printf("Wysyłam SET SPEED (from last): %s\n", fmtHex(raw))
//	//	if err := cln.WriteCharacteristic(chWrite, raw, true); err != nil {
//	//		log.Fatalf("WriteCharacteristic error: %v", err)
//	//	}
//	//	fmt.Println("Wysłano.")
//	//}
//
//	//initFrames := []string{
//	//	"FD-08-A0-00-F0-01-00-01-9A-FE",
//	//	"FD-07-A0-01-0F-07-01-BF-FE",
//	//	"FD-08-A0-03-01-A9-FC-00-51-FE",
//	//}
//	//for _, h := range initFrames {
//	//	raw, _ := decodeHex(h)
//	//	fmt.Printf("INIT %s\n", fmtHex(raw))
//	//	if err := cln.WriteCharacteristic(chWrite, raw, false); err != nil {
//	//		log.Printf("INIT write err: %v", err)
//	//	}
//	//	time.Sleep(100 * time.Millisecond)
//	//}
//
//	if pendingSpeed >= 0 {
//		go func() {
//			// poczekaj aż lastStatus się pojawi
//			for i := 0; i < 50; i++ { // ~5s
//				if len(lastStatus) == 18 {
//					break
//				}
//				time.Sleep(100 * time.Millisecond)
//			}
//			if len(lastStatus) != 18 {
//				log.Printf("no lastStatus after wait")
//				return
//			}
//
//			raw, err := buildSetSpeedFromLast(pendingSpeed, forcedOp) // albo bez forcedOp
//			if err != nil {
//				log.Printf("build cmd error: %v", err)
//				return
//			}
//			fmt.Printf("SEND (async) %s\n", fmtHex(raw))
//
//			// Dla props=8 używamy write-request => noRsp=false
//			if err := cln.WriteCharacteristic(chWrite, raw, false); err != nil {
//				log.Printf("WriteCharacteristic error: %v", err)
//			} else {
//				fmt.Println("SENT.")
//			}
//		}()
//	}
//
//	if *start {
//		raw := buildStart18()
//		fmt.Printf("Wysyłam START: %s\n", fmtHex(raw))
//		if err := cln.WriteCharacteristic(chWrite, raw, false); err != nil {
//			log.Fatalf("WriteCharacteristic error: %v", err)
//		}
//		fmt.Println("START wysłany.")
//	}
//
//	if *sendHex != "" {
//		raw, err := decodeHex(*sendHex)
//		if err != nil {
//			log.Fatalf("send hex decode error: %v", err)
//		}
//		fmt.Printf("Wysyłam na FFF2: %s\n", fmtHex(raw))
//		// false = Write Without Response (często działa najlepiej)
//		if err := cln.WriteCharacteristic(chWrite, raw, false); err != nil {
//			log.Fatalf("WriteCharacteristic error: %v", err)
//		}
//		fmt.Println("Wysłano.")
//	}
//
//	fmt.Println("Nasłuchuję. Ctrl+C aby zakończyć.")
//	waitForSignal()
//	fmt.Println("Koniec.")
//}
//
//func checksumXOR(frame []byte) byte {
//	// XOR ze wszystkich bajtów od indeksu 1 do (len-3), czyli pomijamy SOF (FD) i końcówkę [chk, FE]
//	var x byte
//	for i := 1; i < len(frame)-2; i++ {
//		x ^= frame[i]
//	}
//	return x
//}
//
//var lastStatus []byte
//
//func buildSetSpeed18(speedKmh float64) []byte {
//	target := byte(speedKmh * 10.0) // 3.0 -> 0x1E
//
//	frame := make([]byte, 18)
//	frame[0] = 0xFD
//	frame[1] = 0x10
//	frame[2] = 0xA0 // COMMAND (nasza hipoteza nadal)
//	frame[3] = 0x02
//	frame[4] = target // target speed
//	frame[5] = 0x00   // current speed = 0 (ważna zmiana)
//	// reszta 0
//	frame[17] = 0xFE
//
//	frame[16] = 0
//	frame[16] = chkSum(frame)
//	return frame
//}
//
//func buildSetSpeed(speedKmh float64) []byte {
//	s := byte(speedKmh * 10.0) // 3.0 -> 30 (0x1E)
//
//	// Ramka komendy (hipoteza zostaje ta sama):
//	// FD 05 A0 <speed> <chk> FE
//	frame := []byte{0xFD, 0x05, 0xA0, s, 0x00, 0xFE}
//
//	// checksum = suma bajtów (od index 1), z wyzerowanym polem checksum
//	frame[len(frame)-2] = 0
//	frame[len(frame)-2] = chkSum(frame)
//
//	return frame
//}
//
//func waitForSignal() {
//	ch := make(chan os.Signal, 1)
//	signal.Notify(ch, syscall.SIGINT, syscall.SIGTERM)
//	<-ch
//}
//
//func buildStart18() []byte {
//	// START = pełna ramka, ale speed = 0
//	// bazujemy dokładnie na STATUS, tylko:
//	// typ = A0 (command)
//	// state = 01 (start / run init)
//
//	frame := make([]byte, 18)
//	frame[0] = 0xFD
//	frame[1] = 0x10
//	frame[2] = 0xA0 // COMMAND
//	frame[3] = 0x01 // START
//	frame[4] = 0x00
//	frame[5] = 0x00
//	// 6..15 = 0
//	frame[17] = 0xFE
//
//	frame[16] = 0
//	frame[16] = chkSum(frame)
//
//	return frame
//}
//
//func chkSum(frame []byte) byte {
//	// suma bajtów od [1..len-3] modulo 256
//	var s int
//	for i := 1; i < len(frame)-2; i++ {
//		s += int(frame[i])
//	}
//	return byte(s & 0xFF)
//}
//
//func chkXor(frame []byte) byte {
//	var x byte
//	for i := 1; i < len(frame)-2; i++ {
//		x ^= frame[i]
//	}
//	return x
//}
//
//func chkSumWithFD(frame []byte) byte {
//	// suma bajtów od [0..len-3] (włącznie z FD) modulo 256
//	var s int
//	for i := 0; i < len(frame)-2; i++ {
//		s += int(frame[i])
//	}
//	return byte(s & 0xFF)
//}

package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"sync"
	"syscall"
	"time"

	"treadmill/bluetooth"
)

func clampSpeed(v float64) float64 {
	return bluetooth.ClampSpeed(v)
}

func clampIncline(v int) int {
	return bluetooth.ClampIncline(v)
}

// --- HTTP panel (opcjonalny) ---

type panelServer struct {
	tm *bluetooth.BleTreadmill

	wmu    sync.Mutex
	status WorkoutStatus
	cancel context.CancelFunc
	events chan string // workout events do SSE
}

func (p *panelServer) routes() http.Handler {
	mux := http.NewServeMux()

	mux.HandleFunc("/status", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("content-type", "application/json; charset=utf-8")
		_ = json.NewEncoder(w).Encode(p.tm.Last())
	})

	mux.HandleFunc("/start", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "POST only", http.StatusMethodNotAllowed)
			return
		}
		_ = p.tm.Send(r.Context(), bluetooth.Command{Start: true})
		w.WriteHeader(http.StatusNoContent)
	})

	mux.HandleFunc("/stop", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "POST only", http.StatusMethodNotAllowed)
			return
		}
		_ = p.tm.Send(r.Context(), bluetooth.Command{Stop: true})
		w.WriteHeader(http.StatusNoContent)
	})

	mux.HandleFunc("/speed", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "POST only", http.StatusMethodNotAllowed)
			return
		}
		v := r.URL.Query().Get("value")
		f, err := strconv.ParseFloat(v, 64)
		if err != nil {
			http.Error(w, "bad value", http.StatusBadRequest)
			return
		}
		_ = p.tm.Send(r.Context(), bluetooth.Command{Speed: &f, ReleaseAfter: true})
		w.WriteHeader(http.StatusNoContent)
	})

	mux.HandleFunc("/incline", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "POST only", http.StatusMethodNotAllowed)
			return
		}
		v := r.URL.Query().Get("value")
		i, err := strconv.Atoi(v)
		if err != nil {
			http.Error(w, "bad value", http.StatusBadRequest)
			return
		}
		_ = p.tm.Send(r.Context(), bluetooth.Command{Incline: &i, ReleaseAfter: true})
		w.WriteHeader(http.StatusNoContent)
	})

	// ---- WORKOUT API (na zewnątrz, normalnie) ----

	mux.HandleFunc("/workout", func(w http.ResponseWriter, r *http.Request) {
		switch r.Method {
		case http.MethodGet:
			w.Header().Set("content-type", "application/json; charset=utf-8")
			_ = json.NewEncoder(w).Encode(p.getStatus())

		case http.MethodPost:
			var plan WorkoutPlan
			if err := json.NewDecoder(r.Body).Decode(&plan); err != nil {
				http.Error(w, "bad json", http.StatusBadRequest)
				return
			}
			if len(plan.Steps) == 0 {
				http.Error(w, "empty steps", http.StatusBadRequest)
				return
			}
			for i, s := range plan.Steps {
				if s.DurationSec <= 0 {
					http.Error(w, fmt.Sprintf("step %d durationSec must be > 0", i), http.StatusBadRequest)
					return
				}
				if s.Speed < 0 || s.Speed > 22 {
					http.Error(w, fmt.Sprintf("step %d speed out of range 0..22", i), http.StatusBadRequest)
					return
				}
				if s.Incline != nil && (*s.Incline < 0 || *s.Incline > 22) {
					http.Error(w, fmt.Sprintf("step %d incline out of range 0..22", i), http.StatusBadRequest)
					return
				}
			}
			p.setPlan(plan)
			w.WriteHeader(http.StatusNoContent)

		default:
			http.Error(w, "GET/POST only", http.StatusMethodNotAllowed)
		}
	})

	mux.HandleFunc("/workout/start", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "POST only", http.StatusMethodNotAllowed)
			return
		}
		if err := p.StartWorkout(context.Background()); err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	})

	mux.HandleFunc("/workout/stop", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "POST only", http.StatusMethodNotAllowed)
			return
		}
		p.StopWorkout()
		w.WriteHeader(http.StatusNoContent)
	})

	// ---- SSE ----

	mux.HandleFunc("/events", func(w http.ResponseWriter, r *http.Request) {
		fl, ok := w.(http.Flusher)
		if !ok {
			http.Error(w, "no flusher", http.StatusInternalServerError)
			return
		}
		w.Header().Set("content-type", "text/event-stream")
		w.Header().Set("cache-control", "no-cache")
		w.Header().Set("connection", "keep-alive")

		// initial
		init := p.tm.Last()
		fmt.Fprintf(w, "event: status\ndata: %s\n\n", mustJSON(init))
		fl.Flush()

		sub := p.tm.Subscribe()
		for {
			select {
			case <-r.Context().Done():
				return
			case n := <-sub:
				fmt.Fprintf(w, "event: status\ndata: %s\n\n", mustJSON(n))
				fl.Flush()
			case ev := <-p.events:
				fmt.Fprintf(w, "event: workout\ndata: %s\n\n", ev)
				fl.Flush()
			}
		}
	})

	// ---- index na końcu ----

	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("content-type", "text/html; charset=utf-8")
		fmt.Fprint(w, `<!doctype html>
<html><head><meta charset="utf-8"><title>Treadmill Panel</title></head>
<body>
<h2>Treadmill Panel</h2>

<button onclick="post('/start')">START</button>
<button onclick="post('/stop')">STOP</button>

<hr>

<label>Speed (km/h): <input id="speed" value="3.0"></label>
<button onclick="post('/speed?value='+encodeURIComponent(document.getElementById('speed').value))">Set Speed</button>

<br><br>

<label>Incline: <input id="incline" value="3"></label>
<button onclick="post('/incline?value='+encodeURIComponent(document.getElementById('incline').value))">Set Incline</button>

<hr>

<h3>Workout</h3>
<p>Wpisz kroki (jeden na linię): <code>10m speed 7</code> albo <code>3m speed 12 incline 3</code></p>
<textarea id="plan" rows="8" cols="60">10m speed 7
3m speed 12
2m speed 5</textarea><br>
<button onclick="uploadPlan(false)">Upload</button>
<button onclick="uploadPlan(true)">Upload + Start</button>
<button onclick="post('/workout/stop')">Workout Stop</button>

<pre id="out"></pre>

<script>
async function post(path){ await fetch(path,{method:'POST'}); }

function parseDurationToSec(tok){
  tok = tok.trim().toLowerCase();
  if(/^\d+s$/.test(tok)) return parseInt(tok.slice(0,-1),10);
  if(/^\d+m$/.test(tok)) return parseInt(tok.slice(0,-1),10)*60;
  // fallback: sama liczba = sekundy
  if(/^\d+$/.test(tok)) return parseInt(tok,10);
  throw new Error("Bad duration: " + tok);
}

function buildPlanFromText(txt){
  const lines = txt.split('\n').map(l=>l.trim()).filter(Boolean);
  const steps = [];

  for(const line of lines){
    const parts = line.split(/\s+/);
    if(parts.length < 3) throw new Error("Bad line: " + line);

    const durationSec = parseDurationToSec(parts[0]);

    let speed = null;
    let incline = null;
    let labelParts = [];

    for(let i=1;i<parts.length;i++){
      const p = parts[i].toLowerCase();

      if(p === 'speed'){
        if(i+1 >= parts.length) throw new Error("Missing speed value in: " + line);
        const v = parseFloat(parts[i+1]);
        if(Number.isNaN(v)) throw new Error("Bad speed value in: " + line);
        speed = v;
        i++;
        continue;
      }

      if(p === 'incline'){
        if(i+1 >= parts.length) throw new Error("Missing incline value in: " + line);
        const v = parseInt(parts[i+1],10);
        if(Number.isNaN(v)) throw new Error("Bad incline value in: " + line);
        incline = v;
        i++;
        continue;
      }

      // wszystko inne jako label
      labelParts.push(parts[i]);
    }

    if(speed === null) throw new Error("Missing speed in line: " + line);

    const step = { durationSec, speed };
    if(incline !== null) step.incline = incline;
    if(labelParts.length) step.label = labelParts.join(" ");

    steps.push(step);
  }

  return { name: "Workout", steps };
}

async function uploadPlan(start){
  const txt = document.getElementById('plan').value;
  const plan = buildPlanFromText(txt);
  await fetch('/workout', {
    method:'POST',
    headers:{'content-type':'application/json'},
    body: JSON.stringify(plan)
  });
  if(start) await post('/workout/start');
}

const out = document.getElementById('out');
const es = new EventSource('/events');
es.addEventListener('status', (e) => { out.textContent = "STATUS\n" + e.data + "\n\n" + out.textContent; });
es.addEventListener('workout', (e) => { out.textContent = "WORKOUT\n" + e.data + "\n\n" + out.textContent; });
</script>

</body></html>`)
	})

	return mux
}

func mustJSON(v any) string {
	b, _ := json.Marshal(v)
	return string(b)
}

// --- main CLI ---

func main() {
	var (
		addrOrName = flag.String("addr", "", "Adres (32-hex jak u Ciebie) albo substring nazwy urządzenia.")
		name       = flag.String("name", "RZ_TreadMil", "Fallback substring nazwy (gdy -addr puste).")
		sendHex    = flag.String("send", "", "Wyślij surową ramkę HEX na FFF2 (np. FB-03-A2-01-01-A7-FC).")
		speed      = flag.Float64("speed", -1, "Ustaw speed (km/h), np. 3.0")
		incline    = flag.Int("incline", -1, "Ustaw incline (0-22), np. 3")
		start      = flag.Bool("start", false, "START")
		stop       = flag.Bool("stop", false, "STOP")
		release    = flag.Bool("release", true, "Po MASTER speed/incline wyślij SLAVE START (oddaj kontrolę panelowi).")
		panel      = flag.Bool("panel", false, "Uruchom panel HTTP")
		httpAddr   = flag.String("http", "127.0.0.1:8080", "Adres panelu HTTP, np. 0.0.0.0:8080")
	)
	flag.Parse()

	target := *addrOrName
	if target == "" {
		target = *name
	}

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	tm := bluetooth.NewBleTreadmill()
	if err := tm.Connect(ctx, target); err != nil {
		log.Fatalf("Connect error: %v", err)
	}
	defer tm.Close()

	// jeśli user podał -send -> wyślij raw
	if *sendHex != "" {
		raw, err := bluetooth.DecodeHex(*sendHex)
		if err != nil {
			log.Fatalf("decode send hex error: %v", err)
		}
		log.Printf("TX RAW: %s", bluetooth.FormatHex(raw))
		if err := tm.SendRaw(ctx, raw); err != nil {
			log.Fatalf("write error: %v", err)
		}
	}

	// komendy z flag
	if *start {
		_ = tm.Send(ctx, bluetooth.Command{Start: true})
	}
	if *stop {
		_ = tm.Send(ctx, bluetooth.Command{Stop: true})
	}
	if *speed >= 0 {
		v := *speed
		_ = tm.Send(ctx, bluetooth.Command{Speed: &v, ReleaseAfter: *release})
	}
	if *incline >= 0 {
		v := *incline
		_ = tm.Send(ctx, bluetooth.Command{Incline: &v, ReleaseAfter: *release})
	}

	// panel
	if *panel {
		ps := &panelServer{tm: tm, events: make(chan string, 50)}
		srv := &http.Server{Addr: *httpAddr, Handler: ps.routes()}

		go func() {
			log.Printf("Panel: http://%s", *httpAddr)
			if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
				log.Printf("HTTP error: %v", err)
			}
		}()

		// loguj statusy
		go func() {
			for n := range tm.Subscribe() {
				log.Printf("NOTIFY state=%d speed=%.1f incline=%d time=%ds dist=%d cal=%d raw=%s",
					n.State, n.Speed, n.Incline, n.TimeSeconds, n.DistanceUnits, n.CaloriesUnits, n.RawHex)
			}
		}()
	} else {
		// jak nie ma panelu, to i tak wypisz kilka notify i trzymaj do Ctrl+C
		go func() {
			for n := range tm.Subscribe() {
				log.Printf("NOTIFY state=%d speed=%.1f incline=%d time=%ds dist=%d cal=%d raw=%s",
					n.State, n.Speed, n.Incline, n.TimeSeconds, n.DistanceUnits, n.CaloriesUnits, n.RawHex)
			}
		}()
	}

	log.Println("Działa. Ctrl+C aby zakończyć.")
	waitForSignal()
	log.Println("Koniec.")
}

func waitForSignal() {
	ch := make(chan os.Signal, 1)
	signal.Notify(ch, syscall.SIGINT, syscall.SIGTERM)
	<-ch
}

type WorkoutStep struct {
	DurationSec int     `json:"durationSec"`       // ile sekund trwa krok
	Speed       float64 `json:"speed"`             // km/h
	Incline     *int    `json:"incline,omitempty"` // opcjonalnie
	Label       string  `json:"label,omitempty"`   // np. "rozgrzewka"
}

type WorkoutPlan struct {
	Name  string        `json:"name"`
	Steps []WorkoutStep `json:"steps"`
}

type WorkoutStatus struct {
	Running       bool        `json:"running"`
	Plan          WorkoutPlan `json:"plan"`
	StepIndex     int         `json:"stepIndex"`     // 0-based
	StepRemaining int         `json:"stepRemaining"` // sekundy do końca kroku
	StartedAt     time.Time   `json:"startedAt"`
}

func (p *panelServer) setPlan(plan WorkoutPlan) {
	p.wmu.Lock()
	defer p.wmu.Unlock()
	p.status.Plan = plan
	p.status.StepIndex = 0
	p.status.StepRemaining = 0
}

func (p *panelServer) getStatus() WorkoutStatus {
	p.wmu.Lock()
	defer p.wmu.Unlock()
	return p.status
}

func (p *panelServer) stopWorkoutLocked() {
	if p.cancel != nil {
		p.cancel()
		p.cancel = nil
	}
	p.status.Running = false
	p.status.StepIndex = 0
	p.status.StepRemaining = 0
}

func (p *panelServer) StopWorkout() {
	p.wmu.Lock()
	p.stopWorkoutLocked()
	p.wmu.Unlock()

	select {
	case p.events <- `{"type":"workout","event":"stopped"}`:
	default:
	}
}

func (p *panelServer) StartWorkout(ctx context.Context) error {
	p.wmu.Lock()
	// stop poprzedniego jeśli był
	p.stopWorkoutLocked()

	if len(p.status.Plan.Steps) == 0 {
		p.wmu.Unlock()
		return fmt.Errorf("workout plan is empty")
	}

	runCtx, cancel := context.WithCancel(ctx)
	p.cancel = cancel
	p.status.Running = true
	p.status.StartedAt = time.Now()
	p.status.StepIndex = 0
	p.status.StepRemaining = p.status.Plan.Steps[0].DurationSec
	p.wmu.Unlock()

	select {
	case p.events <- `{"type":"workout","event":"started"}`:
	default:
	}

	go p.runWorkoutWithAutoStart(runCtx)
	return nil
}

func (p *panelServer) runWorkoutWithAutoStart(ctx context.Context) {
	// 1️⃣ MASTER START
	_ = p.tm.Send(ctx, bluetooth.Command{Start: true})
	log.Println("Workout: START sent")

	// 2️⃣ czekamy aż bieżnia faktycznie ruszy
	if !p.waitForRunningState(ctx, 5*time.Second) {
		log.Println("Workout: treadmill did not enter RUNNING state")
		return
	}

	// 3️⃣ mała pauza bezpieczeństwa (firmware!)
	time.Sleep(500 * time.Millisecond)

	// 4️⃣ normalny workout
	p.runWorkout(ctx)

	// 5️⃣ po zakończeniu → STOP
	_ = p.tm.Send(context.Background(), bluetooth.Command{Stop: true})
	log.Println("Workout: STOP sent")
}

func (p *panelServer) waitForRunningState(ctx context.Context, timeout time.Duration) bool {
	deadline := time.After(timeout)

	for {
		select {
		case <-ctx.Done():
			return false
		case <-deadline:
			return false
		case n := <-p.tm.Subscribe():
			// u Ciebie RUNNING = 2 (TYPE_STATE_RUNNING)
			if n.State == 2 {
				return true
			}
		}
	}
}

func (p *panelServer) runWorkout(ctx context.Context) {
	tick := time.NewTicker(1 * time.Second)
	defer tick.Stop()

	// helper: wyślij ustawienia kroku
	applyStep := func(step WorkoutStep) {
		s := clampSpeed(step.Speed)
		cmd := bluetooth.Command{
			Speed:        &s,
			ReleaseAfter: true, // to jest to, co Ci robi “nie blokuj panelu”
		}
		if step.Incline != nil {
			inc := clampIncline(*step.Incline)
			cmd.Incline = &inc
		}
		_ = p.tm.Send(ctx, cmd)

		ev := map[string]any{
			"type":  "workout",
			"event": "step",
			"label": step.Label,
			"speed": s,
		}
		if step.Incline != nil {
			ev["incline"] = *step.Incline
		}
		b, _ := json.Marshal(ev)
		select {
		case p.events <- string(b):
		default:
		}
	}

	// start od razu: zastosuj krok 0
	p.wmu.Lock()
	if !p.status.Running || len(p.status.Plan.Steps) == 0 {
		p.wmu.Unlock()
		return
	}
	stepIdx := p.status.StepIndex
	step := p.status.Plan.Steps[stepIdx]
	p.wmu.Unlock()

	applyStep(step)

	for {
		select {
		case <-ctx.Done():
			return
		case <-tick.C:
			p.wmu.Lock()
			if !p.status.Running {
				p.wmu.Unlock()
				return
			}
			if p.status.StepRemaining > 0 {
				p.status.StepRemaining--
			}
			rem := p.status.StepRemaining
			idx := p.status.StepIndex
			plan := p.status.Plan
			p.wmu.Unlock()

			// co sekundę możemy (opcjonalnie) wysłać progress event
			if rem%5 == 0 { // co 5s dla spokoju
				ev := map[string]any{
					"type":  "workout",
					"event": "tick",
					"step":  idx,
					"rem":   rem,
				}
				b, _ := json.Marshal(ev)
				select {
				case p.events <- string(b):
				default:
				}
			}

			if rem <= 0 {
				// przechodzimy do następnego kroku
				nextIdx := idx + 1
				if nextIdx >= len(plan.Steps) {
					// koniec
					p.wmu.Lock()
					p.status.Running = false
					p.cancel = nil
					p.wmu.Unlock()

					select {
					case p.events <- `{"type":"workout","event":"finished"}`:
					default:
					}
					return
				}

				p.wmu.Lock()
				p.status.StepIndex = nextIdx
				p.status.StepRemaining = plan.Steps[nextIdx].DurationSec
				step = plan.Steps[nextIdx]
				p.wmu.Unlock()

				applyStep(step)
			}
		}
	}
}
