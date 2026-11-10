// recgram-awg: клиент AmneziaWG для RecGram без системного VPN.
//
// Туннель AmneziaWG (amneziawg-go) работает в userspace-сетевом стеке (gVisor netstack) внутри
// этого процесса, а наружу открывается только SOCKS5 на 127.0.0.1 с логином и паролем.
// Сетевой модуль Telegram ходит через этот SOCKS5, остальные приложения телефона туннель не видят.
//
// Настройки приходят JSON-объектом через stdin (на диск не пишутся):
//
//	{"listen":"127.0.0.1:PORT","user":"…","pass":"…","endpoint":"IP:PORT","conf":"[Interface]…"}
//
// conf — обычный файл AmneziaWG/WireGuard (.conf); endpoint — адрес сервера, уже переведённый
// приложением в IP (у Go на Android нет системного DNS). Ключи и адреса в вывод не печатаются.
package main

import (
	"bufio"
	"context"
	"crypto/subtle"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/amnezia-vpn/amneziawg-go/v3/conn"
	"github.com/amnezia-vpn/amneziawg-go/v3/device"
	"github.com/amnezia-vpn/amneziawg-go/v3/tun/netstack"
)

type input struct {
	Listen   string `json:"listen"`
	User     string `json:"user"`
	Pass     string `json:"pass"`
	Endpoint string `json:"endpoint"`
	Conf     string `json:"conf"`
}

func fail(format string, a ...any) {
	fmt.Printf("error: "+format+"\n", a...)
	os.Exit(1)
}

func main() {
	if len(os.Args) > 1 && os.Args[1] == "version" {
		fmt.Println("recgram-awg")
		return
	}
	raw, err := io.ReadAll(io.LimitReader(os.Stdin, 1<<20))
	if err != nil {
		fail("stdin: %v", err)
	}
	var in input
	if err := json.Unmarshal(raw, &in); err != nil {
		fail("настройки: %v", err)
	}
	cfg, err := parseConf(in.Conf, in.Endpoint)
	if err != nil {
		fail("конфиг: %v", err)
	}
	tun, tnet, err := netstack.CreateNetTUN(cfg.addrs, cfg.dns, cfg.mtu)
	if err != nil {
		fail("netstack: %v", err)
	}
	dev := device.NewDevice(tun, conn.NewDefaultBind(), device.NewLogger(device.LogLevelError, "awg: "))
	if err := dev.IpcSet(cfg.uapi); err != nil {
		fail("настройка туннеля: %v", err)
	}
	if err := dev.Up(); err != nil {
		fail("запуск туннеля: %v", err)
	}
	ln, err := net.Listen("tcp", in.Listen)
	if err != nil {
		fail("SOCKS5: %v", err)
	}
	fmt.Println("started")
	go serveSocks(ln, in.User, in.Pass, tnet)

	sig := make(chan os.Signal, 1)
	signal.Notify(sig, syscall.SIGTERM, syscall.SIGINT)
	<-sig
	ln.Close()
	dev.Close()
}

// ---------------------------------------------------------------- конфиг

type conf struct {
	addrs []netip.Addr
	dns   []netip.Addr
	mtu   int
	uapi  string
}

// Параметры [Interface] (имя в .conf в нижнем регистре → имя в UAPI), значение передаётся как есть:
// AmneziaWG 1.x/2.0 (Jc…I5) и 3.x (диапазоны таймингов и добавки к размеру пакетов).
var awgKeys = map[string]string{
	"jc": "jc", "jmin": "jmin", "jmax": "jmax", "s1": "s1", "s2": "s2", "s3": "s3", "s4": "s4",
	"h1": "h1", "h2": "h2", "h3": "h3", "h4": "h4", "i1": "i1", "i2": "i2", "i3": "i3", "i4": "i4", "i5": "i5",
	"contentpaddingaddition": "content_padding_addition",
	"rekeyaftertime":         "rekey_after_time",
	"rekeytimeout":           "rekey_timeout",
	"rejectaftertime":        "reject_after_time",
	"keepalivetimeout":       "keepalive_timeout",
	"maxhandshakeattempts":   "max_handshake_attempts",
}

// Флаги [Interface]: on/off, yes/no, true/false, 1/0.
var awgFlags = map[string]string{
	"randomtrailers": "random_trailers",
	"disablecookies": "disable_cookies",
}

func flag(v string) (string, error) {
	switch strings.ToLower(v) {
	case "on", "yes", "true", "1":
		return "true", nil
	case "off", "no", "false", "0":
		return "false", nil
	}
	return "", fmt.Errorf("ожидается on или off, а не %q", v)
}

func parseConf(text, endpoint string) (*conf, error) {
	c := &conf{mtu: 1280}
	var dev strings.Builder
	type peer struct {
		pub   string
		lines []string
	}
	var peers []*peer
	section := ""
	peerCount := 0
	add := func(format string, a ...any) { // параметр текущего [Peer]
		p := peers[len(peers)-1]
		p.lines = append(p.lines, fmt.Sprintf(format, a...))
	}
	sc := bufio.NewScanner(strings.NewReader(text))
	sc.Buffer(make([]byte, 64*1024), 1<<20)
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		if line == "" || line[0] == '#' || line[0] == ';' {
			continue
		}
		if strings.HasPrefix(line, "[") {
			section = strings.ToLower(strings.Trim(line, "[] "))
			if section == "peer" {
				peerCount++
				peers = append(peers, &peer{})
			}
			continue
		}
		k, v, ok := strings.Cut(line, "=")
		if !ok {
			continue
		}
		key := strings.ToLower(strings.TrimSpace(k))
		val := strings.TrimSpace(v)
		switch section {
		case "interface":
			switch {
			case key == "privatekey":
				h, err := keyHex(val)
				if err != nil {
					return nil, fmt.Errorf("PrivateKey: %w", err)
				}
				fmt.Fprintf(&dev, "private_key=%s\n", h)
			case key == "address":
				for _, a := range splitList(val) {
					p, err := netip.ParsePrefix(a)
					if err != nil {
						ad, err2 := netip.ParseAddr(a)
						if err2 != nil {
							return nil, fmt.Errorf("Address: %w", err)
						}
						p = netip.PrefixFrom(ad, ad.BitLen())
					}
					c.addrs = append(c.addrs, p.Addr())
				}
			case key == "dns":
				for _, a := range splitList(val) {
					if ad, err := netip.ParseAddr(a); err == nil {
						c.dns = append(c.dns, ad)
					}
				}
			case key == "mtu":
				if n, err := strconv.Atoi(val); err == nil && n >= 576 && n <= 9000 {
					c.mtu = n
				}
			case awgKeys[key] != "":
				if val != "" {
					fmt.Fprintf(&dev, "%s=%s\n", awgKeys[key], val)
				}
			case awgFlags[key] != "":
				f, err := flag(val)
				if err != nil {
					return nil, fmt.Errorf("%s: %w", strings.TrimSpace(k), err)
				}
				fmt.Fprintf(&dev, "%s=%s\n", awgFlags[key], f)
			case key == "headerprotectionkey":
				h, err := keyHex(val)
				if err != nil {
					return nil, fmt.Errorf("HeaderProtectionKey: %w", err)
				}
				fmt.Fprintf(&dev, "header_protection_key=%s\n", h)
			case key == "listenport", key == "table", key == "fwmark", key == "saveconfig",
				strings.HasPrefix(key, "pre"), strings.HasPrefix(key, "post"):
				// не нужны без системного интерфейса
			default:
				fmt.Printf("warning: параметр %s не поддерживается и пропущен\n", k)
			}
		case "peer":
			switch key {
			case "publickey":
				h, err := keyHex(val)
				if err != nil {
					return nil, fmt.Errorf("PublicKey: %w", err)
				}
				peers[len(peers)-1].pub = h
				if peerCount == 1 && endpoint != "" {
					add("endpoint=%s", endpoint)
				}
			case "presharedkey":
				h, err := keyHex(val)
				if err != nil {
					return nil, fmt.Errorf("PresharedKey: %w", err)
				}
				add("preshared_key=%s", h)
			case "endpoint":
				if peerCount == 1 && endpoint != "" {
					continue
				}
				ep, err := resolveEndpoint(val)
				if err != nil {
					return nil, fmt.Errorf("Endpoint: %w", err)
				}
				add("endpoint=%s", ep)
			case "allowedips":
				for _, a := range splitList(val) {
					if _, err := netip.ParsePrefix(a); err != nil {
						return nil, fmt.Errorf("AllowedIPs: %w", err)
					}
					add("allowed_ip=%s", a)
				}
			case "persistentkeepalive":
				if n, err := strconv.Atoi(val); err == nil && n > 0 {
					add("persistent_keepalive_interval=%d", n)
				}
			}
		}
	}
	if err := sc.Err(); err != nil {
		return nil, err
	}
	if !strings.Contains(dev.String(), "private_key=") {
		return nil, errors.New("нет PrivateKey")
	}
	if peerCount == 0 {
		return nil, errors.New("нет [Peer]")
	}
	if len(c.addrs) == 0 {
		return nil, errors.New("нет Address")
	}
	if len(c.dns) == 0 {
		c.dns = []netip.Addr{netip.MustParseAddr("1.1.1.1"), netip.MustParseAddr("8.8.8.8")}
	}
	var b strings.Builder
	b.WriteString(dev.String())
	b.WriteString("replace_peers=true\n")
	for _, p := range peers {
		if p.pub == "" {
			return nil, errors.New("нет PublicKey в [Peer]")
		}
		b.WriteString("public_key=" + p.pub + "\n")
		for _, l := range p.lines {
			b.WriteString(l + "\n")
		}
	}
	c.uapi = b.String()
	return c, nil
}

func splitList(s string) []string {
	var out []string
	for _, p := range strings.Split(s, ",") {
		if p = strings.TrimSpace(p); p != "" {
			out = append(out, p)
		}
	}
	return out
}

func keyHex(b64 string) (string, error) {
	b, err := base64.StdEncoding.DecodeString(b64)
	if err != nil || len(b) != 32 {
		return "", errors.New("неверный ключ")
	}
	return hex.EncodeToString(b), nil
}

func resolveEndpoint(ep string) (string, error) {
	host, port, err := net.SplitHostPort(ep)
	if err != nil {
		return "", err
	}
	if _, err := netip.ParseAddr(host); err == nil {
		return ep, nil
	}
	ips, err := net.LookupHost(host)
	if err != nil || len(ips) == 0 {
		return "", fmt.Errorf("не удалось узнать адрес сервера")
	}
	return net.JoinHostPort(ips[0], port), nil
}

// ---------------------------------------------------------------- SOCKS5 (только CONNECT)

func serveSocks(ln net.Listener, user, pass string, tnet *netstack.Net) {
	for {
		c, err := ln.Accept()
		if err != nil {
			return
		}
		go handle(c, user, pass, tnet)
	}
}

func handle(c net.Conn, user, pass string, tnet *netstack.Net) {
	defer c.Close()
	c.SetDeadline(time.Now().Add(20 * time.Second))
	r := bufio.NewReader(c)
	hdr := make([]byte, 2)
	if _, err := io.ReadFull(r, hdr); err != nil || hdr[0] != 5 {
		return
	}
	methods := make([]byte, hdr[1])
	if _, err := io.ReadFull(r, methods); err != nil {
		return
	}
	want := byte(0x00)
	if user != "" {
		want = 0x02
	}
	found := false
	for _, m := range methods {
		if m == want {
			found = true
		}
	}
	if !found {
		c.Write([]byte{5, 0xff})
		return
	}
	c.Write([]byte{5, want})
	if want == 0x02 {
		u, p, err := readAuth(r)
		if err != nil {
			return
		}
		okU := subtle.ConstantTimeCompare([]byte(u), []byte(user)) == 1
		okP := subtle.ConstantTimeCompare([]byte(p), []byte(pass)) == 1
		if !okU || !okP {
			c.Write([]byte{1, 1})
			return
		}
		c.Write([]byte{1, 0})
	}
	req := make([]byte, 4)
	if _, err := io.ReadFull(r, req); err != nil || req[0] != 5 {
		return
	}
	var host string
	switch req[3] {
	case 1:
		b := make([]byte, 4)
		if _, err := io.ReadFull(r, b); err != nil {
			return
		}
		host = net.IP(b).String()
	case 4:
		b := make([]byte, 16)
		if _, err := io.ReadFull(r, b); err != nil {
			return
		}
		host = net.IP(b).String()
	case 3:
		n, err := r.ReadByte()
		if err != nil {
			return
		}
		b := make([]byte, n)
		if _, err := io.ReadFull(r, b); err != nil {
			return
		}
		host = string(b)
	default:
		reply(c, 8)
		return
	}
	pb := make([]byte, 2)
	if _, err := io.ReadFull(r, pb); err != nil {
		return
	}
	if req[1] != 1 { // только CONNECT
		reply(c, 7)
		return
	}
	addr := net.JoinHostPort(host, strconv.Itoa(int(binary.BigEndian.Uint16(pb))))
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	out, err := tnet.DialContext(ctx, "tcp", addr)
	cancel()
	if err != nil {
		reply(c, 5)
		return
	}
	defer out.Close()
	reply(c, 0)
	c.SetDeadline(time.Time{})

	done := make(chan struct{}, 2)
	go func() {
		io.Copy(out, r) // в r могут быть данные, пришедшие сразу за запросом
		closeWrite(out)
		done <- struct{}{}
	}()
	go func() {
		io.Copy(c, out)
		closeWrite(c)
		done <- struct{}{}
	}()
	<-done
	<-done
}

func readAuth(r *bufio.Reader) (string, string, error) {
	v, err := r.ReadByte()
	if err != nil || v != 1 {
		return "", "", errors.New("auth")
	}
	read := func() (string, error) {
		n, err := r.ReadByte()
		if err != nil {
			return "", err
		}
		b := make([]byte, n)
		_, err = io.ReadFull(r, b)
		return string(b), err
	}
	u, err := read()
	if err != nil {
		return "", "", err
	}
	p, err := read()
	return u, p, err
}

func reply(c net.Conn, code byte) {
	c.Write([]byte{5, code, 0, 1, 0, 0, 0, 0, 0, 0})
}

func closeWrite(c net.Conn) {
	if cw, ok := c.(interface{ CloseWrite() error }); ok {
		cw.CloseWrite()
	} else {
		c.Close()
	}
}
