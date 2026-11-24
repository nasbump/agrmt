package main

import (
	"bytes"
	"log"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"agrmt/internal/proto"
)

// 日志中的来源地址要保留端口，便于区分 NAT 后同一 IP 的多条连接。
func TestRemoteAddrKeepsPort(t *testing.T) {
	r := httptest.NewRequest("GET", "/ws/app", nil)
	r.RemoteAddr = "192.168.1.20:51234"
	if got := newConnLog("android", r, "").remote; got != "192.168.1.20:51234" {
		t.Fatalf("来源地址应为 192.168.1.20:51234，实际 %q", got)
	}
}

// 连接在 hello 之前断开时，断开日志要补打接入记录，身份未知时用 "-" 占位。
func TestConnLogFallsBackForUnknownIdentity(t *testing.T) {
	r := httptest.NewRequest("GET", "/ws/cli", nil)
	r.RemoteAddr = "10.0.0.9:1234"
	cl := newConnLog("cli", r, "")
	cl.closed()
	if !cl.logged {
		t.Fatal("closed 应补打接入日志")
	}
	if cl.remote != "10.0.0.9:1234" || cl.group != "" || cl.session != "" {
		t.Fatalf("连接信息不符: %+v", cl)
	}
	if orDash(cl.group) != "-" || orDash(cl.session) != "-" {
		t.Fatal("未知身份应显示为 -")
	}
}

// app 先按 group 接入，attach 后再补充会话名。
func TestConnLogIdentify(t *testing.T) {
	r := httptest.NewRequest("GET", "/ws/app?group=g1", nil)
	r.RemoteAddr = "10.0.0.9:1234"
	cl := newConnLog("android", r, "g1")
	cl.connected()
	cl.connected() // 幂等
	cl.identify("g1", "build")
	if cl.group != "g1" || cl.session != "build" {
		t.Fatalf("身份信息不符: %+v", cl)
	}
}

// syncBuffer 是可并发读写的日志缓冲区：测试读取时服务端协程可能仍在写日志。
type syncBuffer struct {
	mu  sync.Mutex
	buf bytes.Buffer
}

func (b *syncBuffer) Write(p []byte) (int, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.buf.Write(p)
}

func (b *syncBuffer) String() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.buf.String()
}

// captureLog 临时接管日志输出，返回读取函数。
func captureLog(t *testing.T) func() string {
	t.Helper()
	buf := &syncBuffer{}
	oldOut, oldFlags := log.Writer(), log.Flags()
	log.SetOutput(buf)
	log.SetFlags(0)
	t.Cleanup(func() {
		log.SetOutput(oldOut)
		log.SetFlags(oldFlags)
	})
	return buf.String
}

// cli 接入与断开都应打印来源 IP、group、会话名，断开时带存活时间。
func TestCLIConnectionLogged(t *testing.T) {
	readLog := captureLog(t)
	srv, _ := startTestServer(t)

	cli := dial(t, wsURL(srv.URL, "/ws/cli"))
	sendJSON(t, cli, proto.Message{Type: proto.TypeHello, Group: "g", Name: "s", Gen: "gen1"})
	_ = cli.Close()

	deadline := time.Now().Add(3 * time.Second)
	for {
		out := readLog()
		if strings.Contains(out, "[连接接入] apptype=cli") && strings.Contains(out, "[连接断开] apptype=cli") {
			if !strings.Contains(out, "group=g session=s") {
				t.Fatalf("日志缺少 group/session: %s", out)
			}
			if !strings.Contains(out, "存活=") {
				t.Fatalf("断开日志缺少存活时间: %s", out)
			}
			if !strings.Contains(out, "addr=127.0.0.1:") {
				t.Fatalf("日志缺少来源 IP 与端口: %s", out)
			}
			return
		}
		if time.Now().After(deadline) {
			t.Fatalf("等待连接日志超时: %s", out)
		}
		time.Sleep(10 * time.Millisecond)
	}
}

// app 连接接入时即打印 group，attach 后断开日志带上会话名。
func TestAppConnectionLogged(t *testing.T) {
	readLog := captureLog(t)
	srv, _ := startTestServer(t)

	cli := dial(t, wsURL(srv.URL, "/ws/cli"))
	sendJSON(t, cli, proto.Message{Type: proto.TypeHello, Group: "g", Name: "s", Gen: "gen1"})

	app := dial(t, wsURL(srv.URL, "/ws/app?group=g"))
	sendJSON(t, app, proto.Message{Type: proto.TypeAttach, Name: "s"})
	readUntil(t, app, nil, proto.TypeStatus)
	_ = app.Close()

	deadline := time.Now().Add(3 * time.Second)
	for {
		out := readLog()
		if strings.Contains(out, "[连接断开] apptype=android addr=127.0.0.1:") &&
			strings.Contains(out, "group=g session=s") {
			if !strings.Contains(out, "[连接接入] apptype=android") {
				t.Fatalf("缺少 app 接入日志: %s", out)
			}
			return
		}
		if time.Now().After(deadline) {
			t.Fatalf("等待 app 断开日志超时: %s", out)
		}
		time.Sleep(10 * time.Millisecond)
	}
}
