package main

import (
	"bytes"
	"fmt"
	"io"
	"strings"
	"sync"
	"time"

	"craftersy.com/treadmill/src/go/lib/protocol"
)

// recorder writes every event to the log file and the interesting ones to
// the console. Each line starts with HH:MM:SS.mmm, matching the camera's
// HHMMSS.jpg file names.
type recorder struct {
	mu      sync.Mutex
	file    io.Writer
	console io.Writer
	now     func() time.Time
	last    []byte
}

func (r *recorder) write(kind, text string, toConsole bool) {
	r.mu.Lock()
	defer r.mu.Unlock()
	line := fmt.Sprintf("%s %-4s %s\n", r.now().Format("15:04:05.000"), kind, text)
	_, _ = io.WriteString(r.file, line)
	if toConsole {
		_, _ = io.WriteString(r.console, line)
	}
}

func (r *recorder) info(format string, args ...any) {
	r.write("INFO", fmt.Sprintf(format, args...), true)
}

func (r *recorder) mark(note string) { r.write("MARK", note, true) }

func (r *recorder) tx(frame []byte, line string) { r.write("TX", hexString(frame)+" | "+line, true) }

func (r *recorder) txError(line string, err error) { r.write("ERR", line+" | "+err.Error(), true) }

// rx logs a notification. A frame identical to the previous one is logged
// with "=" and kept off the console.
func (r *recorder) rx(frame []byte) {
	r.mu.Lock()
	same := bytes.Equal(frame, r.last)
	r.last = append(r.last[:0], frame...)
	r.mu.Unlock()

	if same {
		r.write("RX", hexString(frame)+" =", false)
		return
	}
	r.write("RX", hexString(frame)+" | "+describe(frame), true)
}

// describe decodes a status frame into a short human-readable summary.
func describe(frame []byte) string {
	st, err := protocol.ParseStatus(frame)
	if err != nil {
		return "? " + err.Error()
	}
	if st.Compact {
		return fmt.Sprintf("%v (compact)", st.State)
	}
	return fmt.Sprintf("%v  target %v  current %v  incline %d  %v  %d m  %.1f kcal  %d bpm",
		st.State, st.TargetSpeed, st.CurrentSpeed, st.Incline, st.Elapsed, st.Distance, st.Calories, st.HeartRate)
}

func hexString(b []byte) string {
	var sb strings.Builder
	for i, v := range b {
		if i > 0 {
			sb.WriteByte('-')
		}
		fmt.Fprintf(&sb, "%02X", v)
	}
	return sb.String()
}
