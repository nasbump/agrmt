package main

import (
	"bytes"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/gorilla/websocket"

	"agrmt/internal/proto"
)

// fakeSrv 模拟 agrmt-srv 的 /ws/cli 端点，记录 cli 上报的消息与原始输出。
type fakeSrv struct {
	t   *testing.T
	srv *httptest.Server

	mu   sync.Mutex
	conn *websocket.Conn
	bins []byte

	texts chan proto.Message
	ready chan struct{}
}

func newFakeSrv(t *testing.T) *fakeSrv {
	t.Helper()
	fs := &fakeSrv{
		t:     t,
		texts: make(chan proto.Message, 64),
		ready: make(chan struct{}),
	}
	mux := http.NewServeMux()
	mux.HandleFunc("/ws/cli", fs.handle)
	fs.srv = httptest.NewServer(mux)
	t.Cleanup(fs.srv.Close)
	return fs
}

func (fs *fakeSrv) url() string { return fs.srv.URL }

func (fs *fakeSrv) handle(w http.ResponseWriter, r *http.Request) {
	ws, err := (&websocket.Upgrader{}).Upgrade(w, r, nil)
	if err != nil {
		fs.t.Errorf("升级失败: %v", err)
		return
	}
	defer ws.Close()

	fs.mu.Lock()
	fs.conn = ws
	fs.mu.Unlock()
	close(fs.ready)

	for {
		typ, data, err := ws.ReadMessage()
		if err != nil {
			return
		}
		if typ == websocket.BinaryMessage {
			fs.mu.Lock()
			fs.bins = append(fs.bins, data...)
			fs.mu.Unlock()
			continue
		}
		m, err := proto.Decode(data)
		if err != nil {
			fs.t.Errorf("解码失败: %v", err)
			continue
		}
		fs.texts <- m
	}
}

func (fs *fakeSrv) send(typ int, p []byte) {
	fs.mu.Lock()
	ws := fs.conn
	fs.mu.Unlock()
	if err := ws.WriteMessage(typ, p); err != nil {
		fs.t.Fatalf("发送失败: %v", err)
	}
}

func (fs *fakeSrv) sendText(m proto.Message) {
	raw, err := proto.Encode(m)
	if err != nil {
		fs.t.Fatalf("编码失败: %v", err)
	}
	fs.send(websocket.TextMessage, raw)
}

func (fs *fakeSrv) sendBinary(p string) { fs.send(websocket.BinaryMessage, []byte(p)) }

// nextText 等待指定类型的控制消息。
func (fs *fakeSrv) nextText(want string, timeout time.Duration) proto.Message {
	fs.t.Helper()
	deadline := time.After(timeout)
	for {
		select {
		case m := <-fs.texts:
			if m.Type == want {
				return m
			}
		case <-deadline:
			fs.t.Fatalf("等待 %s 消息超时", want)
		}
	}
}

// waitOutput 等待累计的二进制输出包含 want。
func (fs *fakeSrv) waitOutput(want string, timeout time.Duration) {
	fs.t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		fs.mu.Lock()
		ok := strings.Contains(string(fs.bins), want)
		fs.mu.Unlock()
		if ok {
			return
		}
		time.Sleep(5 * time.Millisecond)
	}
	fs.mu.Lock()
	got := string(fs.bins)
	fs.mu.Unlock()
	fs.t.Fatalf("等待输出包含 %q 超时，实际: %q", want, got)
}

func TestRunLocalPassthrough(t *testing.T) {
	var out bytes.Buffer
	code := run(options{
		argv:   []string{"sh", "-c", "printf hi; exit 3"},
		stdin:  strings.NewReader(""),
		stdout: &out,
		stderr: io.Discard,
	})
	if code != 3 {
		t.Fatalf("退出码不符: %d", code)
	}
	if !strings.Contains(out.String(), "hi") {
		t.Fatalf("本地输出缺失: %q", out.String())
	}
}

func TestRunInvalidAgentReturnsOne(t *testing.T) {
	code := run(options{
		argv:   []string{"/nonexistent/agent-binary"},
		stdin:  strings.NewReader(""),
		stdout: io.Discard,
		stderr: io.Discard,
	})
	if code != 1 {
		t.Fatalf("启动失败应返回 1，实际: %d", code)
	}
}

func TestRunReportsToServer(t *testing.T) {
	fs := newFakeSrv(t)
	var out bytes.Buffer
	codeCh := make(chan int, 1)
	go func() {
		codeCh <- run(options{
			argv:   []string{"sh"},
			wsAddr: fs.url(),
			group:  "g1",
			name:   "s1",
			stdin:  strings.NewReader(""),
			stdout: &out,
			stderr: io.Discard,
		})
	}()

	select {
	case <-fs.ready:
	case <-time.After(5 * time.Second):
		t.Fatal("cli 未连接 srv")
	}

	hello := fs.nextText(proto.TypeHello, 5*time.Second)
	if hello.Group != "g1" || hello.Name != "s1" || hello.Gen == "" {
		t.Fatalf("hello 内容不符: %+v", hello)
	}
	if hello.Cols != 80 || hello.Rows != 24 {
		t.Fatalf("非终端环境应采用 80x24 回退: %+v", hello)
	}

	// srv → cli 的二进制帧应写入 PTY 并由 agent 执行。
	fs.sendBinary("echo REMOTE-TAG\r")
	fs.waitOutput("REMOTE-TAG", 5*time.Second)

	fs.sendBinary("exit 7\r")
	exit := fs.nextText(proto.TypeExit, 5*time.Second)
	if exit.Code == nil || *exit.Code != 7 {
		t.Fatalf("exit 消息不符: %+v", exit)
	}

	select {
	case code := <-codeCh:
		if code != 7 {
			t.Fatalf("cli 退出码不符: %d", code)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("cli 未退出")
	}
	if !strings.Contains(out.String(), "REMOTE-TAG") {
		t.Fatalf("本地输出也应包含 agent 输出: %q", out.String())
	}
}

func TestNormalizeURL(t *testing.T) {
	cases := []struct {
		in      string
		want    string
		wantErr bool
	}{
		{in: "127.0.0.1:8989", want: "ws://127.0.0.1:8989/ws/cli"},
		{in: "ws://host:1/ignored?x=1", want: "ws://host:1/ws/cli"},
		{in: "http://host:2", want: "ws://host:2/ws/cli"},
		{in: "https://host", want: "wss://host/ws/cli"},
		{in: "ftp://host", wantErr: true},
	}
	for _, c := range cases {
		got, err := normalizeURL(c.in)
		if c.wantErr {
			if err == nil {
				t.Fatalf("%s 应返回错误，实际: %s", c.in, got)
			}
			continue
		}
		if err != nil {
			t.Fatalf("%s 解析失败: %v", c.in, err)
		}
		if got != c.want {
			t.Fatalf("%s 结果不符: got %s want %s", c.in, got, c.want)
		}
	}
}
