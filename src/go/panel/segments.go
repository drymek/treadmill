package main

import "strings"

// Seven-segment glyphs, three rows high and three columns wide, like the
// red LED digits on the treadmill console.
var glyphs = map[rune][3]string{
	'0': {" _ ", "| |", "|_|"},
	'1': {"   ", "  |", "  |"},
	'2': {" _ ", " _|", "|_ "},
	'3': {" _ ", " _|", " _|"},
	'4': {"   ", "|_|", "  |"},
	'5': {" _ ", "|_ ", " _|"},
	'6': {" _ ", "|_ ", "|_|"},
	'7': {" _ ", "  |", "  |"},
	'8': {" _ ", "|_|", "|_|"},
	'9': {" _ ", "|_|", " _|"},
	'-': {"   ", " _ ", "   "},
	' ': {"   ", "   ", "   "},
}

// segments renders s (digits, '-', ' ', '.' and ':') as three lines of
// seven-segment art. A '.' lights the decimal point of the previous digit;
// ':' becomes a one-column separator.
func segments(s string) string {
	var rows [3]strings.Builder
	for _, r := range s {
		switch r {
		case '.':
			rows[0].WriteString(" ")
			rows[1].WriteString(" ")
			rows[2].WriteString(".")
		case ':':
			rows[0].WriteString(" ")
			rows[1].WriteString(".")
			rows[2].WriteString(".")
		default:
			g, ok := glyphs[r]
			if !ok {
				g = glyphs[' ']
			}
			for i := range rows {
				rows[i].WriteString(g[i])
			}
		}
	}
	return rows[0].String() + "\n" + rows[1].String() + "\n" + rows[2].String()
}
