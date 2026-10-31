package main

import tea "charm.land/bubbletea/v2"

// action is what a key press asks the treadmill to do.
type action int

const (
	actNone action = iota
	actStart
	actStop
	actSpeedUp
	actSpeedDown
	actInclineUp
	actInclineDown
	actSpeedPreset
	actInclinePreset
	actHelp
	actQuit
)

// keyAction maps a key to an action and, for presets, the target value
// (tenths of km/h for speed, level for incline).
//
// Keys mirror the console: START and STOP, +/− for speed (p and m work
// without Shift), arrows for incline, and the four quick keys of each row.
func keyAction(msg tea.KeyPressMsg) (action, int) {
	switch msg.String() {
	case "enter":
		return actStart, 0
	case "space", "x":
		return actStop, 0
	case "+", "=", "p", "right":
		return actSpeedUp, 0
	case "-", "_", "m", "left":
		return actSpeedDown, 0
	case "up", "k":
		return actInclineUp, 0
	case "down", "j":
		return actInclineDown, 0
	case "3":
		return actSpeedPreset, 30
	case "6":
		return actSpeedPreset, 60
	case "9":
		return actSpeedPreset, 90
	case "0":
		return actSpeedPreset, 120
	case "a":
		return actInclinePreset, 3
	case "s":
		return actInclinePreset, 6
	case "d":
		return actInclinePreset, 9
	case "f":
		return actInclinePreset, 12
	case "?", "h":
		return actHelp, 0
	case "q", "esc", "ctrl+c":
		return actQuit, 0
	}
	return actNone, 0
}
