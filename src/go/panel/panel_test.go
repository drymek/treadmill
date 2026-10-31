package main

import (
	"errors"
	"strings"
	"testing"
	"time"

	tea "charm.land/bubbletea/v2"

	"craftersy.com/treadmill/src/go/lib/protocol"
	"craftersy.com/treadmill/src/go/lib/treadmill"
)

func key(s string) tea.KeyPressMsg {
	switch s {
	case "enter":
		return tea.KeyPressMsg{Code: tea.KeyEnter}
	case "space":
		return tea.KeyPressMsg{Code: tea.KeySpace, Text: " "}
	case "up":
		return tea.KeyPressMsg{Code: tea.KeyUp}
	case "down":
		return tea.KeyPressMsg{Code: tea.KeyDown}
	case "esc":
		return tea.KeyPressMsg{Code: tea.KeyEscape}
	case "ctrl+c":
		return tea.KeyPressMsg{Code: 'c', Mod: tea.ModCtrl}
	}
	r := []rune(s)[0]
	return tea.KeyPressMsg{Code: r, Text: s}
}

func TestKeyAction(t *testing.T) {
	tests := map[string]struct {
		act   action
		value int
	}{
		"enter": {actStart, 0}, "space": {actStop, 0}, "x": {actStop, 0},
		"p": {actSpeedUp, 0}, "+": {actSpeedUp, 0}, "=": {actSpeedUp, 0},
		"m": {actSpeedDown, 0}, "-": {actSpeedDown, 0},
		"up": {actInclineUp, 0}, "down": {actInclineDown, 0}, "k": {actInclineUp, 0}, "j": {actInclineDown, 0},
		"3": {actSpeedPreset, 30}, "6": {actSpeedPreset, 60}, "9": {actSpeedPreset, 90}, "0": {actSpeedPreset, 120},
		"a": {actInclinePreset, 3}, "s": {actInclinePreset, 6}, "d": {actInclinePreset, 9}, "f": {actInclinePreset, 12},
		"?": {actHelp, 0}, "q": {actQuit, 0}, "esc": {actQuit, 0}, "ctrl+c": {actQuit, 0},
		"z": {actNone, 0},
	}
	for k, want := range tests {
		act, value := keyAction(key(k))
		if act != want.act || value != want.value {
			t.Errorf("key %q (%q) = %v/%d, want %v/%d", k, key(k).String(), act, value, want.act, want.value)
		}
	}
}

func TestSegments(t *testing.T) {
	got := segments("3.5")
	want := " _   _ \n" +
		" _| |_ \n" +
		" _|. _|"
	if got != want {
		t.Errorf("segments(3.5) =\n%s\nwant\n%s", got, want)
	}
	if lines := strings.Split(segments("12:05"), "\n"); len(lines) != 3 || len(lines[0]) != 13 {
		t.Errorf("segments(12:05) has unexpected shape: %q", lines)
	}
}

// driver runs a model like tea.Program does, but synchronously: each command
// runs in its own goroutine and its message is fed back into Update.
type driver struct {
	t    *testing.T
	m    model
	msgs chan tea.Msg
	quit bool
}

func newDriver(t *testing.T) *driver {
	d := &driver{t: t, m: newModel("simulator", simulatorConnector(2*time.Millisecond)), msgs: make(chan tea.Msg, 256)}
	d.run(d.m.Init())
	t.Cleanup(func() {
		if d.m.client != nil {
			_ = d.m.client.Close()
		}
	})
	d.until(func(m model) bool { return m.hasStatus })
	return d
}

func (d *driver) run(cmd tea.Cmd) {
	if cmd == nil {
		return
	}
	go func() {
		msg := cmd()
		if batch, ok := msg.(tea.BatchMsg); ok {
			for _, c := range batch {
				d.run(c)
			}
			return
		}
		d.msgs <- msg
	}()
}

func (d *driver) update(msg tea.Msg) {
	if _, ok := msg.(tea.QuitMsg); ok {
		d.quit = true
		return
	}
	next, cmd := d.m.Update(msg)
	d.m = next.(model)
	d.run(cmd)
}

func (d *driver) press(keys ...string) {
	for _, k := range keys {
		d.update(key(k))
	}
}

func (d *driver) until(cond func(model) bool) {
	d.t.Helper()
	deadline := time.After(5 * time.Second)
	for !cond(d.m) {
		select {
		case msg := <-d.msgs:
			d.update(msg)
		case <-deadline:
			d.t.Fatalf("condition not met; status %+v, note %q", d.m.status, d.m.note)
		}
	}
}

func (d *driver) running() {
	d.t.Helper()
	d.press("enter")
	d.until(func(m model) bool { return m.status.State == protocol.StateRunning && m.pending == 0 })
}

func target(speed protocol.Speed, incline protocol.Incline) func(model) bool {
	return func(m model) bool {
		return m.pending == 0 && m.status.TargetSpeed == speed && m.status.Incline == incline
	}
}

func TestStartAndQuickSpeedPresses(t *testing.T) {
	d := newDriver(t)
	d.running()

	d.press("p", "p", "p") // faster than the treadmill acknowledges
	d.until(target(13, 0))

	d.press("m")
	d.until(target(12, 0))

	d.press("+", "-", "=")
	d.until(target(13, 0))
}

func TestInclineKeepsSpeedAndPresets(t *testing.T) {
	d := newDriver(t)
	d.running()

	d.press("6")
	d.until(target(60, 0))
	d.press("up", "up")
	d.until(target(60, 2))
	d.press("down")
	d.until(target(60, 1))
	d.press("f")
	d.until(target(60, 12))
	d.press("0")
	d.until(target(120, 12))
}

func TestLimitsAreCheckedBeforeSending(t *testing.T) {
	d := newDriver(t)
	d.running()

	d.press("m") // already at 1.0 km/h
	if !d.m.noteErr || d.m.pending != 0 {
		t.Errorf("speed below minimum: note %q, pending %d", d.m.note, d.m.pending)
	}
	d.press("down") // already at incline 0
	if !d.m.noteErr || d.m.pending != 0 {
		t.Errorf("incline below minimum: note %q, pending %d", d.m.note, d.m.pending)
	}
}

func TestSpeedKeysNeedARunningBelt(t *testing.T) {
	d := newDriver(t)
	d.press("p")
	if !d.m.noteErr || d.m.pending != 0 || !strings.Contains(d.m.note, "ENTER") {
		t.Errorf("note %q, pending %d", d.m.note, d.m.pending)
	}
}

func TestStopKey(t *testing.T) {
	d := newDriver(t)
	d.running()
	d.press("space")
	d.until(func(m model) bool { return m.status.State == protocol.StateIdle })
}

func TestQuitStopsTheBeltFirst(t *testing.T) {
	d := newDriver(t)
	d.running()
	d.press("q")
	if d.quit {
		t.Fatal("quit before stopping the belt")
	}
	d.until(func(m model) bool { return m.quitting })
	for !d.quit {
		select {
		case msg := <-d.msgs:
			d.update(msg)
		case <-time.After(5 * time.Second):
			t.Fatal("never quit")
		}
	}
	if st, _ := d.m.client.Status(); st.State != protocol.StateStopping && st.State != protocol.StateStopped && st.State != protocol.StateIdle {
		t.Errorf("belt state after quit: %v", st.State)
	}
}

func TestQuitWhenIdleIsImmediate(t *testing.T) {
	d := newDriver(t)
	d.press("q")
	d.until(func(model) bool { return true })
	select {
	case msg := <-d.msgs:
		d.update(msg)
	case <-time.After(time.Second):
	}
	if !d.quit {
		// tea.Quit is returned as a command; run it.
		t.Fatal("q on an idle belt did not quit")
	}
}

func TestView(t *testing.T) {
	m := newModel("simulator", nil)
	m.link = linkConnected
	m.hasStatus = true
	m.status = protocol.Status{State: protocol.StateRunning, TargetSpeed: 35, CurrentSpeed: 30, Incline: 2,
		Elapsed: 83 * time.Second, Distance: 50, Calories: 3.5, HeartRate: 94}
	out := m.render()
	for _, want := range []string{"SPEED", "INCLINE", "PULSE", "TIME", "DISTANCE", "CALORIES", "RUNNING",
		"belt 3.0 km/h", "[enter]", "START", "[p]", "[m]", " _|. _|"} {
		if !strings.Contains(out, want) {
			t.Errorf("view lacks %q\n%s", want, out)
		}
	}

	m.link = linkLost
	if !strings.Contains(m.render(), "CONNECTION LOST") {
		t.Error("no banner when the connection is lost")
	}
	m.help = true
	if !strings.Contains(m.render(), "speed −0.1 km/h") {
		t.Error("help not shown")
	}
}

func TestConnectionEvents(t *testing.T) {
	m := newModel("RZ_TreadMil", nil)
	next, _ := m.Update(connEventMsg(treadmill.ConnectionEvent{State: treadmill.Disconnected}))
	m = next.(model)
	if m.link != linkLost {
		t.Fatalf("link = %v after disconnect", m.link)
	}
	next, _ = m.Update(connEventMsg(treadmill.ConnectionEvent{State: treadmill.ReconnectFailed, Attempt: 2, Err: errors.New("not found")}))
	m = next.(model)
	if !m.noteErr || !strings.Contains(m.note, "attempt 2") {
		t.Errorf("note %q", m.note)
	}
	next, _ = m.Update(connEventMsg(treadmill.ConnectionEvent{State: treadmill.Connected, Attempt: 3}))
	m = next.(model)
	if m.link != linkConnected || m.note != "Reconnected." {
		t.Errorf("link %v, note %q", m.link, m.note)
	}
}

func TestDescribeErr(t *testing.T) {
	if !strings.Contains(describeErr(treadmill.ErrDisconnected), "last setting") {
		t.Error("ErrDisconnected not explained")
	}
}
