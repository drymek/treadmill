// Command panel is a virtual console for RZ_TreadMil treadmills: the HMS
// display and buttons in a terminal.
//
//	panel                 connect over Bluetooth
//	panel -sim            run against the built-in simulator, no hardware
//
// Quitting stops a moving belt first.
package main

import (
	"context"
	"flag"
	"fmt"
	"os"
	"time"

	tea "charm.land/bubbletea/v2"

	"craftersy.com/treadmill/src/go/lib/bluetooth"
	"craftersy.com/treadmill/src/go/lib/protocol"
	"craftersy.com/treadmill/src/go/lib/treadmill"
	"craftersy.com/treadmill/src/go/lib/treadmilltest"
)

func main() {
	sim := flag.Bool("sim", false, "use the built-in simulator instead of Bluetooth")
	tick := flag.Duration("tick", 1290*time.Millisecond, "status interval of the simulator")
	name := flag.String("name", protocol.DeviceName, "advertised name of the treadmill")
	address := flag.String("address", "", "device address; takes precedence over -name")
	flag.Parse()

	device, connect := *name, bluetoothConnector(bluetooth.Options{Name: *name, Address: *address})
	if *sim {
		device, connect = "simulator", simulatorConnector(*tick)
	}

	final, err := tea.NewProgram(newModel(device, connect)).Run()
	if m, ok := final.(model); ok && m.client != nil {
		safetyStop(m)
		_ = m.client.Close()
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "panel:", err)
		os.Exit(1)
	}
}

// safetyStop stops the belt if it may still be moving, e.g. after a crash
// of the UI or an impatient double "q".
func safetyStop(m model) {
	st, ok := m.client.Status()
	if !ok || (st.State != protocol.StateStarting && st.State != protocol.StateRunning) {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), commandTimeout)
	defer cancel()
	if err := m.client.Stop(ctx); err != nil {
		fmt.Fprintln(os.Stderr, "panel: could not stop the belt:", err)
		fmt.Fprintln(os.Stderr, "panel: STOP IT ON THE CONSOLE OR PULL THE SAFETY KEY")
		return
	}
	fmt.Println("Belt stopped.")
}

func bluetoothConnector(opts bluetooth.Options) connector {
	return func(ctx context.Context, events chan<- treadmill.ConnectionEvent) (*treadmill.Client, error) {
		tr, err := treadmill.DialReconnecting(ctx,
			func(ctx context.Context) (treadmill.Transport, error) { return bluetooth.Dial(ctx, opts) },
			treadmill.WithConnectionHandler(func(e treadmill.ConnectionEvent) {
				select {
				case events <- e:
				default: // the UI is behind; the next event will catch it up
				}
			}),
		)
		if err != nil {
			return nil, err
		}
		return treadmill.New(tr), nil
	}
}

func simulatorConnector(tick time.Duration) connector {
	return func(context.Context, chan<- treadmill.ConnectionEvent) (*treadmill.Client, error) {
		return treadmill.New(treadmilltest.NewSimulator(tick)), nil
	}
}
