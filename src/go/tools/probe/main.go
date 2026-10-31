// Command probe is a protocol research tool. It connects to the treadmill,
// logs every notification with millisecond timestamps and sends commands
// typed on stdin or posted over HTTP, so a session can be lined up with
// photos of the console display.
//
//	probe [-name RZ_TreadMil] [-address ID] [-dir .probe] [-http 127.0.0.1:8090]
//
// Send commands from another terminal with:
//
//	curl -d 'set 2.0 0' http://127.0.0.1:8090/cmd
//
// Unlike the library, probe does not validate values or wait for
// acknowledgements: it sends exactly what it is told to.
package main

import (
	"bufio"
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"
	"time"

	"craftersy.com/treadmill/src/go/lib/bluetooth"
)

func main() {
	name := flag.String("name", "", "advertised name to look for (default RZ_TreadMil)")
	address := flag.String("address", "", "device address; takes precedence over -name")
	dir := flag.String("dir", ".probe", "directory for log files")
	addr := flag.String("http", "127.0.0.1:8090", "HTTP listen address for POST /cmd; empty disables")
	flag.Usage = func() {
		fmt.Fprintf(flag.CommandLine.Output(), "usage: probe [flags]\n\n%s\n\nflags:\n", usage)
		flag.PrintDefaults()
	}
	flag.Parse()

	if err := run(*name, *address, *dir, *addr); err != nil {
		log.Fatal(err)
	}
}

func run(name, address, dir, httpAddr string) error {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	path := filepath.Join(dir, time.Now().Format("2006-01-02_150405")+".log")
	file, err := os.Create(path)
	if err != nil {
		return err
	}
	defer file.Close()
	rec := &recorder{file: file, console: os.Stdout, now: time.Now}
	rec.info("log %s", path)

	tr, err := bluetooth.Dial(ctx, bluetooth.Options{Name: name, Address: address})
	if err != nil {
		return err
	}
	defer tr.Close()
	rec.info("connected to %s (%s)", tr.Name(), tr.Address())

	send := func(line string) error {
		a, err := parseCommand(line)
		if err != nil {
			return err
		}
		if a.frame == nil {
			rec.mark(a.note)
			return nil
		}
		if err := tr.Write(ctx, a.frame); err != nil {
			rec.txError(line, err)
			return err
		}
		rec.tx(a.frame, line)
		return nil
	}

	if httpAddr != "" {
		srv := &http.Server{Addr: httpAddr, Handler: commandHandler(send)}
		go func() {
			if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
				rec.info("http: %v", err)
			}
		}()
		defer srv.Close()
		rec.info("commands: stdin or POST http://%s/cmd", httpAddr)
	}

	go readStdin(send)

	for {
		select {
		case <-ctx.Done():
			rec.info("interrupted")
			return nil
		case frame, ok := <-tr.Frames():
			if !ok {
				rec.info("disconnected")
				return errors.New("connection closed")
			}
			rec.rx(frame)
		}
	}
}

func readStdin(send func(string) error) {
	sc := bufio.NewScanner(os.Stdin)
	for sc.Scan() {
		if line := strings.TrimSpace(sc.Text()); line != "" {
			if err := send(line); err != nil {
				fmt.Fprintln(os.Stderr, err)
			}
		}
	}
}

// commandHandler accepts one command per POST /cmd request body.
func commandHandler(send func(string) error) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("POST /cmd", func(w http.ResponseWriter, r *http.Request) {
		body, err := io.ReadAll(io.LimitReader(r.Body, 4096))
		if err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		line := strings.TrimSpace(string(body))
		if err := send(line); err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		fmt.Fprintf(w, "ok: %s\n", line)
	})
	return mux
}
