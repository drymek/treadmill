package main

import (
	"context"
	"errors"
	"fmt"
	"image/color"
	"strings"
	"time"

	tea "charm.land/bubbletea/v2"
	"charm.land/lipgloss/v2"

	"craftersy.com/treadmill/src/go/lib/protocol"
	"craftersy.com/treadmill/src/go/lib/treadmill"
)

// commandTimeout bounds one command, including its acknowledgement.
const commandTimeout = 5 * time.Second

// connector opens a client. Connection changes after the first connection
// are reported on events.
type connector func(ctx context.Context, events chan<- treadmill.ConnectionEvent) (*treadmill.Client, error)

type link int

const (
	linkConnecting link = iota
	linkConnected
	linkLost
	linkFailed
)

// Messages.
type (
	connectedMsg     struct{ client *treadmill.Client }
	connectFailedMsg struct{ err error }
	statusMsg        protocol.Status
	statusClosedMsg  struct{}
	connEventMsg     treadmill.ConnectionEvent
	resultMsg        struct {
		label string
		err   error
		quit  bool
	}
)

type model struct {
	connect connector
	device  string

	client *treadmill.Client
	sub    <-chan protocol.Status
	events chan treadmill.ConnectionEvent
	link   link

	status    protocol.Status
	hasStatus bool

	// Targets of commands still in flight, so that quick key presses build
	// on each other instead of on a status frame that is not there yet.
	pending     int
	haveWant    bool
	wantSpeed   protocol.Speed
	wantIncline protocol.Incline

	// Commands run one at a time, in the order of the key presses: Bubble Tea
	// runs commands concurrently, and a later "incline 2" must never be
	// overtaken by an earlier "incline 1", nor a STOP by anything.
	jobs    chan job
	results chan resultMsg

	note     string
	noteErr  bool
	help     bool
	quitting bool
}

type job struct {
	label string
	quit  bool
	run   func(context.Context) error
}

func newModel(device string, connect connector) model {
	m := model{
		connect: connect,
		device:  device,
		events:  make(chan treadmill.ConnectionEvent, 16),
		jobs:    make(chan job, 32),
		results: make(chan resultMsg, 32),
		note:    "Searching for " + device + "…",
	}
	go func() {
		for j := range m.jobs {
			ctx, cancel := context.WithTimeout(context.Background(), commandTimeout)
			m.results <- resultMsg{label: j.label, err: j.run(ctx), quit: j.quit}
			cancel()
		}
	}()
	return m
}

func (m model) Init() tea.Cmd {
	connect, events := m.connect, m.events
	return tea.Batch(waitResult(m.results), func() tea.Msg {
		c, err := connect(context.Background(), events)
		if err != nil {
			return connectFailedMsg{err}
		}
		return connectedMsg{c}
	})
}

func waitResult(results <-chan resultMsg) tea.Cmd {
	return func() tea.Msg { return <-results }
}

func waitStatus(sub <-chan protocol.Status) tea.Cmd {
	return func() tea.Msg {
		st, ok := <-sub
		if !ok {
			return statusClosedMsg{}
		}
		return statusMsg(st)
	}
}

func waitEvent(events <-chan treadmill.ConnectionEvent) tea.Cmd {
	return func() tea.Msg { return connEventMsg(<-events) }
}

func (m model) Update(msg tea.Msg) (tea.Model, tea.Cmd) {
	switch msg := msg.(type) {
	case connectedMsg:
		m.client = msg.client
		m.sub = msg.client.Subscribe(context.Background())
		m.link = linkConnected
		m.setNote("Connected. Press ENTER to start.", false)
		return m, tea.Batch(waitStatus(m.sub), waitEvent(m.events))

	case connectFailedMsg:
		m.link = linkFailed
		m.setNote(msg.err.Error(), true)
		return m, nil

	case statusMsg:
		m.status, m.hasStatus = protocol.Status(msg), true
		if m.pending == 0 {
			m.haveWant = false
		}
		return m, waitStatus(m.sub)

	case statusClosedMsg:
		m.link = linkFailed
		m.setNote("Connection closed.", true)
		return m, nil

	case connEventMsg:
		switch msg.State {
		case treadmill.Connected:
			m.link = linkConnected
			if msg.Attempt > 0 {
				m.setNote("Reconnected.", false)
			}
		case treadmill.Disconnected:
			m.link = linkLost
		case treadmill.ReconnectFailed:
			m.link = linkLost
			m.setNote(fmt.Sprintf("Reconnect attempt %d failed: %v", msg.Attempt, msg.Err), true)
		}
		return m, waitEvent(m.events)

	case resultMsg:
		m.pending--
		if msg.err != nil {
			m.haveWant = false
			m.setNote(describeErr(msg.err), true)
		} else if msg.label != "" {
			m.setNote(msg.label, false)
		}
		if msg.quit {
			return m, tea.Quit
		}
		return m, waitResult(m.results)

	case tea.KeyPressMsg:
		return m.handleKey(msg)
	}
	return m, nil
}

func (m *model) setNote(s string, isErr bool) { m.note, m.noteErr = s, isErr }

func describeErr(err error) string {
	switch {
	case errors.Is(err, treadmill.ErrNotRunning):
		return "The belt is not running. Press ENTER to start."
	case errors.Is(err, treadmill.ErrDisconnected):
		return "Not connected. The belt keeps its last setting!"
	case errors.Is(err, treadmill.ErrNotAcknowledged):
		return "The treadmill did not confirm the command."
	}
	return err.Error()
}

func (m model) moving() bool {
	return m.hasStatus && (m.status.State == protocol.StateStarting || m.status.State == protocol.StateRunning)
}

func (m model) handleKey(msg tea.KeyPressMsg) (tea.Model, tea.Cmd) {
	act, value := keyAction(msg)

	switch act {
	case actQuit:
		if m.quitting || m.client == nil || !m.moving() {
			return m, tea.Quit
		}
		m.quitting = true
		m.setNote("Stopping the belt before quitting… (press q again to quit now)", false)
		return m, m.do("", true, func(ctx context.Context) error { return m.client.Stop(ctx) })
	case actHelp:
		m.help = !m.help
		return m, nil
	case actNone:
		return m, nil
	}

	if m.client == nil {
		m.setNote("Not connected yet.", true)
		return m, nil
	}

	switch act {
	case actStart:
		m.setNote("Starting: 3-second countdown…", false)
		return m, m.do("Running. Use p/m or 3/6/9/0 for speed, arrows for incline.", false,
			func(ctx context.Context) error { return m.client.Start(ctx) })
	case actStop:
		m.haveWant = false
		m.setNote("Stopping…", false)
		return m, m.do("Stopped.", false, func(ctx context.Context) error { return m.client.Stop(ctx) })
	}

	if !m.hasStatus || m.status.State != protocol.StateRunning {
		m.setNote("The belt is not running. Press ENTER to start.", true)
		return m, nil
	}

	speed, incline := m.status.TargetSpeed, m.status.Incline
	if m.haveWant {
		speed, incline = m.wantSpeed, m.wantIncline
	}
	switch act {
	case actSpeedUp:
		speed++
	case actSpeedDown:
		speed--
	case actSpeedPreset:
		speed = protocol.Speed(value)
	case actInclineUp:
		incline++
	case actInclineDown:
		if incline == protocol.MinIncline {
			m.setNote("Incline is already at its minimum.", true)
			return m, nil
		}
		incline--
	case actInclinePreset:
		incline = protocol.Incline(value)
	}
	if speed < protocol.MinSpeed || speed > protocol.MaxSpeed {
		m.setNote(fmt.Sprintf("Speed must stay within %v – %v.", protocol.MinSpeed, protocol.MaxSpeed), true)
		return m, nil
	}
	if incline > protocol.MaxIncline {
		m.setNote(fmt.Sprintf("Incline must stay within %d – %d.", protocol.MinIncline, protocol.MaxIncline), true)
		return m, nil
	}

	m.wantSpeed, m.wantIncline, m.haveWant = speed, incline, true
	label := fmt.Sprintf("Set %v, incline %d.", speed, incline)
	return m, m.do(label, false, func(ctx context.Context) error {
		return m.client.SetSpeedIncline(ctx, speed.KMH(), int(incline))
	})
}

// do queues a treadmill command; its result arrives as a resultMsg.
func (m *model) do(label string, quit bool, f func(context.Context) error) tea.Cmd {
	select {
	case m.jobs <- job{label: label, quit: quit, run: f}:
		m.pending++
	default:
		m.haveWant = false
		m.setNote("Too many commands queued; wait a moment.", true)
	}
	return nil
}

// ── View ────────────────────────────────────────────────────────────────

var (
	ledColor    = lipgloss.Color("#FF3B30")
	dimColor    = lipgloss.Color("#5A1E1B")
	labelColor  = lipgloss.Color("#8E8E93")
	frameColor  = lipgloss.Color("#3A3A3C")
	speedColor  = lipgloss.Color("#30B0C7")
	inclColor   = lipgloss.Color("#FFD60A")
	startColor  = lipgloss.Color("#34C759")
	stopColor   = lipgloss.Color("#FF453A")
	brandStyle  = lipgloss.NewStyle().Bold(true).Foreground(lipgloss.Color("#E5E5EA"))
	labelStyle  = lipgloss.NewStyle().Foreground(labelColor)
	ledStyle    = lipgloss.NewStyle().Foreground(ledColor).Bold(true)
	ledOffStyle = lipgloss.NewStyle().Foreground(dimColor)
	cellStyle   = lipgloss.NewStyle().Width(22).Align(lipgloss.Center)
	frameStyle  = lipgloss.NewStyle().Border(lipgloss.RoundedBorder()).BorderForeground(frameColor).Padding(0, 1)
	errStyle    = lipgloss.NewStyle().Foreground(stopColor)
	okStyle     = lipgloss.NewStyle().Foreground(lipgloss.Color("#D1D1D6"))
)

func (m model) View() tea.View {
	v := tea.NewView(m.render())
	v.AltScreen = true
	v.WindowTitle = "Treadmill panel – " + m.device
	return v
}

func (m model) render() string {
	parts := []string{m.header(), m.display(), m.buttons(), m.footer()}
	if m.link == linkLost {
		banner := lipgloss.NewStyle().Bold(true).Foreground(lipgloss.Color("#000000")).Background(stopColor).Padding(0, 1).
			Render("CONNECTION LOST – THE BELT KEEPS ITS LAST SETTING. USE THE CONSOLE STOP OR THE SAFETY KEY.")
		parts = append([]string{banner}, parts...)
	}
	if m.help {
		parts = append(parts, helpText())
	}
	return lipgloss.JoinVertical(lipgloss.Left, parts...)
}

func (m model) header() string {
	var linkText string
	switch m.link {
	case linkConnecting:
		linkText = labelStyle.Render("○ searching…")
	case linkConnected:
		linkText = lipgloss.NewStyle().Foreground(startColor).Render("● connected")
	case linkLost:
		linkText = errStyle.Render("● reconnecting…")
	case linkFailed:
		linkText = errStyle.Render("● not connected")
	}
	left := brandStyle.Render("HMS") + labelStyle.Render("  ·  "+m.device)
	gap := 70 - lipgloss.Width(left) - lipgloss.Width(linkText)
	return " " + left + strings.Repeat(" ", max(gap, 1)) + linkText
}

func (m model) display() string {
	st := m.status
	metrics := m.hasStatus && !st.Compact

	speed := "0.0"
	incline, elapsed, dist, kcal, pulse := "0", "0:00", "0.00", "0.0", "--"
	if metrics {
		speed = fmt.Sprintf("%d.%d", st.TargetSpeed/10, st.TargetSpeed%10)
		incline = fmt.Sprintf("%d", st.Incline)
		elapsed = fmt.Sprintf("%d:%02d", int(st.Elapsed.Minutes()), int(st.Elapsed.Seconds())%60)
		dist = fmt.Sprintf("%.2f", float64(st.Distance)/1000)
		kcal = fmt.Sprintf("%.1f", st.Calories)
		if st.HeartRate > 0 {
			pulse = fmt.Sprintf("%d", st.HeartRate)
		}
	}
	lit := m.hasStatus && m.link == linkConnected

	belt := ""
	if metrics && st.CurrentSpeed != st.TargetSpeed {
		belt = fmt.Sprintf("belt %v", st.CurrentSpeed)
	}
	top := lipgloss.JoinHorizontal(lipgloss.Top,
		cell("INCLINE", incline, "", lit),
		cell("SPEED  km/h", speed, belt, lit),
		cell("PULSE", pulse, "", lit),
	)
	bottom := lipgloss.JoinHorizontal(lipgloss.Top,
		cell("TIME", elapsed, "", lit),
		cell("DISTANCE  km", dist, "", lit),
		cell("CALORIES", kcal, "", lit),
	)
	state := lipgloss.PlaceHorizontal(66, lipgloss.Center, m.stateBadge())
	return frameStyle.Render(lipgloss.JoinVertical(lipgloss.Left, top, "", bottom, "", state))
}

func cell(label, value, sub string, lit bool) string {
	digits := ledOffStyle.Render(segments(value))
	if lit {
		digits = ledStyle.Render(segments(value))
	}
	return cellStyle.Render(lipgloss.JoinVertical(lipgloss.Center,
		labelStyle.Render(label), digits, labelStyle.Render(sub)))
}

func (m model) stateBadge() string {
	if !m.hasStatus {
		return labelStyle.Render("—")
	}
	label := strings.ToUpper(m.status.State.String())
	c := labelColor
	switch m.status.State {
	case protocol.StateStarting:
		c = inclColor
	case protocol.StateRunning:
		c = startColor
	case protocol.StateStopping, protocol.StateStopped:
		c = stopColor
	}
	return lipgloss.NewStyle().Bold(true).Foreground(c).Render("● " + label)
}

func button(key, label string, c color.Color) string {
	return lipgloss.NewStyle().Foreground(c).Render("["+key+"]") + labelStyle.Render(" "+label)
}

func (m model) buttons() string {
	row1 := strings.Join([]string{
		button("enter", "START", startColor),
		button("space", "STOP", stopColor),
		button("m", "−", speedColor),
		button("p", "+", speedColor),
		button("3 6 9 0", "3 6 9 12 km/h", speedColor),
	}, "  ")
	row2 := strings.Join([]string{
		button("↓", "▽", inclColor),
		button("↑", "△", inclColor),
		button("a s d f", "incline 3 6 9 12", inclColor),
		button("?", "help", labelColor),
		button("q", "quit", labelColor),
	}, "  ")
	return " " + row1 + "\n " + row2
}

func (m model) footer() string {
	style := okStyle
	if m.noteErr {
		style = errStyle
	}
	return "\n " + style.Render(m.note)
}

func helpText() string {
	rows := [][2]string{
		{"enter", "start the belt (3 s countdown, then 1.0 km/h)"},
		{"space, x", "stop the belt"},
		{"p, +, →", "speed +0.1 km/h"},
		{"m, -, ←", "speed −0.1 km/h"},
		{"3 6 9 0", "speed 3, 6, 9, 12 km/h"},
		{"↑, k / ↓, j", "incline +1 / −1"},
		{"a s d f", "incline 3, 6, 9, 12"},
		{"q, esc, ctrl+c", "quit (stops a moving belt first)"},
	}
	var b strings.Builder
	b.WriteString("\n")
	for _, r := range rows {
		fmt.Fprintf(&b, "  %s  %s\n", lipgloss.NewStyle().Width(16).Foreground(speedColor).Render(r[0]), labelStyle.Render(r[1]))
	}
	b.WriteString(labelStyle.Render("\n  Speed and incline can only change while the belt runs. Always use the safety key."))
	return b.String()
}
