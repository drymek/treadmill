package workout

import (
	"fmt"
	"regexp"
	"slices"
	"strconv"
	"time"

	"go.yaml.in/yaml/v3"

	"craftersy.com/treadmill/src/go/lib/protocol"
)

// Parse parses and validates a workout file. On failure it returns a nil
// Workout and an Errors value listing every problem found.
func Parse(data []byte) (*Workout, error) {
	var doc yaml.Node
	if err := yaml.Unmarshal(data, &doc); err != nil {
		return nil, Errors{{Msg: err.Error()}}
	}
	p := &parser{}
	w := p.document(&doc)
	if len(p.errs) > 0 {
		slices.SortStableFunc(p.errs, func(a, b *Error) int { return a.Line - b.Line })
		return nil, p.errs
	}
	return w, nil
}

type parser struct {
	errs  Errors
	steps int // steps seen so far, for default names
}

func (p *parser) fail(n *yaml.Node, path, format string, args ...any) {
	p.errs = append(p.errs, &Error{Line: n.Line, Path: path, Msg: fmt.Sprintf(format, args...)})
}

// field is a key of a mapping node with its value.
type field struct {
	key, value *yaml.Node
}

// fields returns the keys of mapping n, reporting keys not in allowed.
func (p *parser) fields(n *yaml.Node, path string, allowed ...string) map[string]field {
	out := make(map[string]field)
	for i := 0; i+1 < len(n.Content); i += 2 {
		k, v := n.Content[i], n.Content[i+1]
		if !slices.Contains(allowed, k.Value) {
			p.fail(k, path, "unknown key %q", k.Value)
			continue
		}
		if _, dup := out[k.Value]; dup {
			p.fail(k, path, "duplicate key %q", k.Value)
			continue
		}
		out[k.Value] = field{k, v}
	}
	return out
}

func (p *parser) document(doc *yaml.Node) *Workout {
	if len(doc.Content) == 0 {
		p.fail(doc, "", "file is empty")
		return nil
	}
	root := doc.Content[0]
	if root.Kind != yaml.MappingNode {
		p.fail(root, "", "top level must be a mapping with version, name and steps")
		return nil
	}
	f := p.fields(root, "", "version", "name", "description", "steps")
	w := &Workout{}

	if v, ok := f["version"]; !ok {
		p.fail(root, "", "missing version")
	} else if n, ok := p.integer(v, ""); ok {
		if n != Version {
			p.fail(v.value, "", "unsupported version %d (this reader supports %d)", n, Version)
		}
		w.Version = n
	}
	if v, ok := f["name"]; !ok {
		p.fail(root, "", "missing name")
	} else {
		w.Name, _ = p.text(v, "")
	}
	if v, ok := f["description"]; ok {
		w.Description, _ = p.text(v, "")
	}
	if v, ok := f["steps"]; !ok {
		p.fail(root, "", "missing steps")
	} else if items, ok := p.list(v, ""); ok {
		for i, item := range items {
			w.Blocks = append(w.Blocks, p.entry(item, fmt.Sprintf("steps[%d]", i)))
		}
	}
	return w
}

// entry parses a top-level list item: a step or a repeat block.
func (p *parser) entry(n *yaml.Node, path string) Block {
	if n.Kind != yaml.MappingNode {
		p.fail(n, path, "must be a step or a repeat block")
		return Block{}
	}
	if hasKey(n, "repeat") {
		return p.repeat(n, path)
	}
	return Block{Repeat: 1, Steps: []Step{p.step(n, path)}}
}

func (p *parser) repeat(n *yaml.Node, path string) Block {
	f := p.fields(n, path, "repeat", "steps")
	b := Block{}
	if r, ok := p.integer(f["repeat"], path); ok {
		if r < 2 {
			p.fail(f["repeat"].value, path, "repeat must be at least 2, got %d", r)
		}
		b.Repeat = r
	}
	v, ok := f["steps"]
	if !ok {
		p.fail(n, path, "repeat block has no steps")
		return b
	}
	items, ok := p.list(v, path)
	if !ok {
		return b
	}
	for i, item := range items {
		itemPath := fmt.Sprintf("%s.steps[%d]", path, i)
		switch {
		case item.Kind != yaml.MappingNode:
			p.fail(item, itemPath, "must be a step")
		case hasKey(item, "repeat"):
			p.fail(item, itemPath, "a repeat block cannot contain another repeat block")
		default:
			b.Steps = append(b.Steps, p.step(item, itemPath))
		}
	}
	return b
}

func (p *parser) step(n *yaml.Node, path string) Step {
	p.steps++
	s := Step{Line: n.Line, Name: fmt.Sprintf("Step %d", p.steps)}
	if v, ok := mapValue(n, "name"); ok && v.Kind == yaml.ScalarNode && v.Value != "" {
		s.Name = v.Value
		path += " (" + v.Value + ")"
	}
	f := p.fields(n, path, "name", "duration", "distance", "pace", "speed", "incline", "note")

	if v, ok := f["name"]; ok {
		p.text(v, path)
	}
	if v, ok := f["note"]; ok {
		s.Note, _ = p.text(v, path)
	}

	duration, hasDuration := f["duration"]
	distance, hasDistance := f["distance"]
	switch {
	case hasDuration && hasDistance:
		p.fail(distance.key, path, "a step has either duration or distance, not both")
	case hasDuration:
		if t, ok := p.text(duration, path); ok {
			if d, err := parseDuration(t); err != nil {
				p.fail(duration.value, path, "duration: %v", err)
			} else {
				s.Duration = d
			}
		}
	case hasDistance:
		if t, ok := p.text(distance, path); ok {
			if m, err := parseDistance(t); err != nil {
				p.fail(distance.value, path, "distance: %v", err)
			} else {
				s.Distance = m
			}
		}
	default:
		p.fail(n, path, "missing duration or distance")
	}

	pace, hasPace := f["pace"]
	speed, hasSpeed := f["speed"]
	switch {
	case hasPace && hasSpeed:
		p.fail(speed.key, path, "a step has either pace or speed, not both")
	case hasPace:
		if t, ok := p.text(pace, path); ok {
			if d, sp, err := parsePace(t); err != nil {
				p.fail(pace.value, path, "pace: %v", err)
			} else {
				s.Pace, s.Speed = d, sp
			}
		}
	case hasSpeed:
		if t, ok := p.text(speed, path); ok {
			if sp, err := parseSpeed(t); err != nil {
				p.fail(speed.value, path, "speed: %v", err)
			} else {
				s.Speed = sp
			}
		}
	default:
		p.fail(n, path, "missing pace or speed")
	}

	if v, ok := f["incline"]; ok {
		if level, ok := p.integer(v, path); ok {
			if level < int(protocol.MinIncline) || level > int(protocol.MaxIncline) {
				p.fail(v.value, path, "incline %d is outside %d–%d", level, protocol.MinIncline, protocol.MaxIncline)
			} else {
				s.Incline, s.InclineSet = protocol.Incline(level), true
			}
		}
	}
	return s
}

// text returns the value of f as a string.
func (p *parser) text(f field, path string) (string, bool) {
	if f.value.Kind != yaml.ScalarNode || f.value.Tag == "!!null" {
		p.fail(f.value, path, "%s must be a text value", f.key.Value)
		return "", false
	}
	return f.value.Value, true
}

// integer returns the value of f as an int.
func (p *parser) integer(f field, path string) (int, bool) {
	if f.value == nil {
		return 0, false
	}
	if f.value.Kind != yaml.ScalarNode || f.value.Tag != "!!int" {
		p.fail(f.value, path, "%s must be a whole number", f.key.Value)
		return 0, false
	}
	n, err := strconv.Atoi(f.value.Value)
	if err != nil {
		p.fail(f.value, path, "%s must be a whole number", f.key.Value)
		return 0, false
	}
	return n, true
}

// list returns the items of sequence f, which must not be empty.
func (p *parser) list(f field, path string) ([]*yaml.Node, bool) {
	if f.value.Kind != yaml.SequenceNode {
		p.fail(f.value, path, "%s must be a list", f.key.Value)
		return nil, false
	}
	if len(f.value.Content) == 0 {
		p.fail(f.value, path, "%s must not be empty", f.key.Value)
		return nil, false
	}
	return f.value.Content, true
}

var (
	durationRE    = regexp.MustCompile(`^(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?$`)
	metresRE      = regexp.MustCompile(`^(\d+)m$`)
	kilometresRE  = regexp.MustCompile(`^(\d+)(?:\.(\d{1,2}))?km$`)
	paceRE        = regexp.MustCompile(`^([1-9]\d*):([0-5]\d)/km$`)
	speedRE       = regexp.MustCompile(`^(\d+)(?:\.(\d))? ?km/h$`)
	errOutOfRange = fmt.Errorf("outside the treadmill range %v–%v", protocol.MinSpeed, protocol.MaxSpeed)
)

// parseDuration parses "1h5m", "3m", "90s", "1m30s".
func parseDuration(s string) (time.Duration, error) {
	m := durationRE.FindStringSubmatch(s)
	if m == nil || s == "" {
		return 0, fmt.Errorf("%q is not like 90s, 3m or 1m30s", s)
	}
	var d time.Duration
	for i, unit := range []time.Duration{time.Hour, time.Minute, time.Second} {
		if m[i+1] != "" {
			n, err := strconv.Atoi(m[i+1])
			if err != nil {
				return 0, fmt.Errorf("%q is too long", s)
			}
			d += time.Duration(n) * unit
		}
	}
	if d <= 0 {
		return 0, fmt.Errorf("%q must be longer than zero", s)
	}
	return d, nil
}

// parseDistance parses "400m" or "2.5km" into metres.
func parseDistance(s string) (int, error) {
	var metres int
	if m := metresRE.FindStringSubmatch(s); m != nil {
		n, err := strconv.Atoi(m[1])
		if err != nil {
			return 0, fmt.Errorf("%q is too long", s)
		}
		metres = n
	} else if m := kilometresRE.FindStringSubmatch(s); m != nil {
		km, err := strconv.Atoi(m[1])
		if err != nil {
			return 0, fmt.Errorf("%q is too long", s)
		}
		frac := m[2]
		for len(frac) < 3 {
			frac += "0"
		}
		n, _ := strconv.Atoi(frac)
		metres = km*1000 + n
	} else {
		return 0, fmt.Errorf("%q is not like 400m or 2.5km", s)
	}
	if metres <= 0 {
		return 0, fmt.Errorf("%q must be longer than zero", s)
	}
	if metres%10 != 0 {
		return 0, fmt.Errorf("%q is not a multiple of 10 m, the treadmill's distance unit", s)
	}
	return metres, nil
}

// parsePace parses "6:30/km" into the pace and the rounded speed.
func parsePace(s string) (time.Duration, protocol.Speed, error) {
	m := paceRE.FindStringSubmatch(s)
	if m == nil {
		return 0, 0, fmt.Errorf("%q is not like 6:30/km", s)
	}
	minutes, err := strconv.Atoi(m[1])
	if err != nil || minutes > 999 {
		return 0, 0, fmt.Errorf("%q is too slow", s)
	}
	secs, _ := strconv.Atoi(m[2])
	total := minutes*60 + secs
	// 3600 s per hour / total s per km, in tenths of km/h, rounded half up.
	tenths := (36000*2 + total) / (2 * total)
	if tenths < int(protocol.MinSpeed) || tenths > int(protocol.MaxSpeed) {
		return 0, 0, fmt.Errorf("%s is %d.%d km/h, %w", s, tenths/10, tenths%10, errOutOfRange)
	}
	return time.Duration(total) * time.Second, protocol.Speed(tenths), nil
}

// parseSpeed parses "5 km/h" or "10.5km/h".
func parseSpeed(s string) (protocol.Speed, error) {
	m := speedRE.FindStringSubmatch(s)
	if m == nil {
		return 0, fmt.Errorf("%q is not like 10.5 km/h", s)
	}
	whole, err := strconv.Atoi(m[1])
	if err != nil || whole > 999 {
		return 0, fmt.Errorf("%q is %w", s, errOutOfRange)
	}
	tenths := whole * 10
	if m[2] != "" {
		tenths += int(m[2][0] - '0')
	}
	if tenths < int(protocol.MinSpeed) || tenths > int(protocol.MaxSpeed) {
		return 0, fmt.Errorf("%q is %w", s, errOutOfRange)
	}
	return protocol.Speed(tenths), nil
}

func mapValue(n *yaml.Node, key string) (*yaml.Node, bool) {
	for i := 0; i+1 < len(n.Content); i += 2 {
		if n.Content[i].Value == key {
			return n.Content[i+1], true
		}
	}
	return nil, false
}

func hasKey(n *yaml.Node, key string) bool {
	_, ok := mapValue(n, key)
	return ok
}
