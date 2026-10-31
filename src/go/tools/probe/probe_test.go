package main

import (
	"bytes"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestParseCommand(t *testing.T) {
	tests := map[string]string{
		"start":                      "FB-03-A2-01-01-A7-FC",
		"start 0":                    "FB-03-A2-01-00-A6-FC",
		"STOP":                       "FB-03-A2-04-01-AA-FC",
		"stop 00":                    "FB-03-A2-04-00-A9-FC",
		"set 3.0 2":                  "FB-07-A1-02-01-1E-02-00-00-CB-FC",
		"set 3,5 0":                  "FB-07-A1-02-01-23-00-00-00-CE-FC",
		"set 25 30":                  "FB-07-A1-02-01-FA-1E-00-00-C3-FC", // no range check on purpose
		"set 1.5 0 0":                "FB-07-A1-02-00-0F-00-00-00-B9-FC",
		"frame A2 02 01":             "FB-03-A2-02-01-A8-FC",
		"frame a1 02 01 0F 00 01 00": "FB-07-A1-02-01-0F-00-01-00-BB-FC",
		"raw FB-03-A2-01-01-A7-FC":   "FB-03-A2-01-01-A7-FC",
		"raw 0xFB 0x03":              "FB-03",
	}
	for line, want := range tests {
		a, err := parseCommand(line)
		if err != nil {
			t.Errorf("%q: %v", line, err)
			continue
		}
		if got := hexString(a.frame); got != want {
			t.Errorf("%q = %s, want %s", line, got, want)
		}
	}
}

func TestParseMark(t *testing.T) {
	a, err := parseCommand("  mark   display: 3.0  km/h ")
	if err != nil || a.frame != nil || a.note != "display: 3.0  km/h" {
		t.Errorf("got %+v, %v", a, err)
	}
}

func TestParseCommandErrors(t *testing.T) {
	for _, line := range []string{"", "mark", "jump", "start 1 2", "start zz", "set 3", "set x 0", "set 30 0",
		"set 3 999", "frame A1 02", "frame A1 02 0G", "raw", "raw F"} {
		if _, err := parseCommand(line); err == nil {
			t.Errorf("%q: expected error", line)
		}
	}
}

func TestRecorderCollapsesRepeatedFrames(t *testing.T) {
	var file, console bytes.Buffer
	clock := time.Date(2026, 10, 31, 8, 37, 40, 98e6, time.Local)
	rec := &recorder{file: &file, console: &console, now: func() time.Time { return clock }}

	idle := []byte{0xFD, 0x05, 0xA1, 0x00, 0x00, 0xA6, 0xFE}
	stopped := []byte{0xFD, 0x10, 0xA1, 0x05, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xB6, 0xFE}
	rec.rx(idle)
	rec.rx(idle)
	rec.rx(stopped)
	rec.rx(idle) // differs from the previous frame, even though seen before
	rec.mark("display: WELCOME")

	want := []string{
		"08:37:40.098 RX   FD-05-A1-00-00-A6-FE | idle (compact)",
		"08:37:40.098 RX   FD-05-A1-00-00-A6-FE =",
		"08:37:40.098 RX   FD-10-A1-05-00-00-00-00-00-00-00-00-00-00-00-00-B6-FE | stopped  target 0.0 km/h",
		"08:37:40.098 RX   FD-05-A1-00-00-A6-FE | idle (compact)",
		"08:37:40.098 MARK display: WELCOME",
	}
	lines := strings.Split(strings.TrimSpace(file.String()), "\n")
	if len(lines) != len(want) {
		t.Fatalf("log has %d lines:\n%s", len(lines), file.String())
	}
	for i := range want {
		if !strings.HasPrefix(lines[i], want[i]) {
			t.Errorf("line %d = %q, want prefix %q", i, lines[i], want[i])
		}
	}
	if strings.Contains(console.String(), " =") {
		t.Errorf("repeated frame shown on console:\n%s", console.String())
	}
}

func TestDescribeMalformed(t *testing.T) {
	if got := describe([]byte{0xFD, 0x05}); !strings.HasPrefix(got, "? ") {
		t.Errorf("describe = %q", got)
	}
}

func TestCommandHandler(t *testing.T) {
	var got []string
	h := commandHandler(func(line string) error {
		if _, err := parseCommand(line); err != nil {
			return err
		}
		got = append(got, line)
		return nil
	})

	rr := httptest.NewRecorder()
	h.ServeHTTP(rr, httptest.NewRequest(http.MethodPost, "/cmd", strings.NewReader("set 2.0 0\n")))
	if rr.Code != http.StatusOK || len(got) != 1 || got[0] != "set 2.0 0" {
		t.Errorf("POST /cmd: %d %q, sent %v", rr.Code, rr.Body.String(), got)
	}

	rr = httptest.NewRecorder()
	h.ServeHTTP(rr, httptest.NewRequest(http.MethodPost, "/cmd", strings.NewReader("jump")))
	if rr.Code != http.StatusBadRequest {
		t.Errorf("bad command: status %d", rr.Code)
	}

	rr = httptest.NewRecorder()
	h.ServeHTTP(rr, httptest.NewRequest(http.MethodGet, "/cmd", nil))
	if rr.Code != http.StatusMethodNotAllowed {
		t.Errorf("GET /cmd: status %d", rr.Code)
	}
}
