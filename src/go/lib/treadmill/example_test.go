package treadmill_test

import (
	"context"
	"fmt"
	"log"
	"time"

	"craftersy.com/treadmill/src/go/lib/protocol"
	"craftersy.com/treadmill/src/go/lib/treadmill"
	"craftersy.com/treadmill/src/go/lib/treadmilltest"
)

// A complete workout against the simulator. With hardware, replace the
// simulator with bluetooth.Dial(ctx, bluetooth.Options{}).
func Example() {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	tm := treadmill.New(treadmilltest.NewSimulator(time.Millisecond))
	defer tm.Close()

	isRunning := func(s protocol.Status) bool { return s.State == protocol.StateRunning }

	if err := tm.Start(ctx); err != nil {
		log.Fatal(err)
	}
	if _, err := tm.WaitFor(ctx, isRunning); err != nil {
		log.Fatal(err)
	}
	if err := tm.SetSpeed(ctx, 3.5); err != nil {
		log.Fatal(err)
	}
	if err := tm.SetIncline(ctx, 2); err != nil { // keeps 3.5 km/h
		log.Fatal(err)
	}
	st, _ := tm.Status()
	fmt.Printf("%v, target %v, incline %d\n", st.State, st.TargetSpeed, st.Incline)

	if err := tm.Stop(ctx); err != nil {
		log.Fatal(err)
	}
	// Output: running, target 3.5 km/h, incline 2
}

func ExampleClient_Subscribe() {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	tm := treadmill.New(treadmilltest.NewSimulator(time.Millisecond))
	defer tm.Close()

	for st := range tm.Subscribe(ctx) {
		fmt.Println(st.State)
		break
	}
	// Output: idle
}
