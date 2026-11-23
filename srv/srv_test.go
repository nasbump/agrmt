package main

import (
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"

	"agrmt/internal/proto"
)

func startTestServer(t *testing.T) (*httptest.Server, *hub) {
	t.Helper()
	h := newHub()
	srv := httptest.NewServer(newMux(h))
	t.Cleanup(srv.Close)
	return srv, h
}

func wsURL(base, path string) string {
	return "ws" + strings.TrimPrefix(base, "http") + path
}

func dial(t *testing.T, url string) *websocket.Conn {
	t.Helper()
	c, _, err := websocket.DefaultDialer.Dial(url, nil)
	if err != nil {
		t.Fatalf("连接 %s 失败: %v", url, err)
	}
	t.Cleanup(func() { _ = c.Close() })
	return c
}

func sendJSON(t *testing.T, c *websocket.Conn, m proto.Message) {
	t.Helper()
	raw, err := proto.Encode(m)
	if err != nil {
		t.Fatalf("编码失败: %v", err)
	}
	if err := c.WriteMessage(websocket.TextMessage, raw); err != nil {
		t.Fatalf("发送失败: %v", err)
	}
}

func sendBin(t *testing.T, c *websocket.Conn, p string) {
	t.Helper()
	if err := c.WriteMessage(websocket.BinaryMessage, []byte(p)); err != nil {
		t.Fatalf("发送二进制失败: %v", err)
	}
}

// readUntil 读取消息直到类型为 want 且满足条件；期间不相关消息被丢弃。
func readUntil(t *testing.T, c *websocket.Conn, pred func(proto.Message) bool, want string) proto.Message {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for {
		if err := c.SetReadDeadline(deadline); err != nil {
			t.Fatalf("设置超时失败: %v", err)
		}
		_, data, err := c.ReadMessage()
		if err != nil {
			t.Fatalf("读取消息失败: %v", err)
		}
		m, err := proto.Decode(data)
		if err != nil {
			t.Fatalf("解码失败: %v", err)
		}
		if m.Type == want && (pred == nil || pred(m)) {
			return m
		}
	}
}

// readBinary 读取一帧二进制数据。
func readBinary(t *testing.T, c *websocket.Conn) string {
	t.Helper()
	if err := c.SetReadDeadline(time.Now().Add(3 * time.Second)); err != nil {
		t.Fatalf("设置超时失败: %v", err)
	}
	typ, data, err := c.ReadMessage()
	if err != nil {
		t.Fatalf("读取二进制失败: %v", err)
	}
	if typ != websocket.BinaryMessage {
		t.Fatalf("期望二进制帧，实际类型 %d", typ)
	}
	return string(data)
}

// waitBuffered 等待 srv 把 cli 的输出写入缓存，避免 app 接入时机竞争。
func waitBuffered(t *testing.T, h *hub, group, name, want string) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if s := h.session(group, name, false); s != nil {
			s.mu.Lock()
			ok := strings.Contains(string(s.buf), want)
			s.mu.Unlock()
			if ok {
				return
			}
		}
		time.Sleep(5 * time.Millisecond)
	}
	t.Fatalf("等待会话缓存包含 %q 超时", want)
}

func TestBufferedOutputReplayedOnAttach(t *testing.T) {
	srv, h := startTestServer(t)
	cli := dial(t, wsURL(srv.URL, "/ws/cli"))
	sendJSON(t, cli, proto.Message{Type: proto.TypeHello, Group: "g", Name: "s", Gen: "gen1", Cols: 100, Rows: 30})
	sendBin(t, cli, "hello\r\n")
	waitBuffered(t, h, "g", "s", "hello")

	app := dial(t, wsURL(srv.URL, "/ws/app?group=g"))
	sendJSON(t, app, proto.Message{Type: proto.TypeAttach, Name: "s"})

	readUntil(t, app, func(m proto.Message) bool { return m.Type == proto.TypeReset }, proto.TypeReset)
	// 状态先于回放，app 据此按会话尺寸初始化终端。
	status := readUntil(t, app, nil, proto.TypeStatus)
	if status.Name != "s" || status.Status != proto.StatusOnline || status.Cols != 100 || status.Rows != 30 {
		t.Fatalf("状态不符: %+v", status)
	}
	if got := readBinary(t, app); got != "hello\r\n" {
		t.Fatalf("回放内容不符: %q", got)
	}
}

func TestAppInputForwardedToCLI(t *testing.T) {
	srv, _ := startTestServer(t)
	cli := dial(t, wsURL(srv.URL, "/ws/cli"))
	sendJSON(t, cli, proto.Message{Type: proto.TypeHello, Group: "g", Name: "s", Gen: "gen1"})

	app := dial(t, wsURL(srv.URL, "/ws/app?group=g"))
	sendJSON(t, app, proto.Message{Type: proto.TypeAttach, Name: "s"})
	readUntil(t, app, nil, proto.TypeStatus)

	sendBin(t, app, "ls -la\r")
	if got := readBinary(t, cli); got != "ls -la\r" {
		t.Fatalf("cli 收到输入不符: %q", got)
	}
}

func TestCLIResizeBroadcast(t *testing.T) {
	srv, _ := startTestServer(t)
	cli := dial(t, wsURL(srv.URL, "/ws/cli"))
	sendJSON(t, cli, proto.Message{Type: proto.TypeHello, Group: "g", Name: "s", Gen: "gen1", Cols: 80, Rows: 24})

	app := dial(t, wsURL(srv.URL, "/ws/app?group=g"))
	sendJSON(t, app, proto.Message{Type: proto.TypeAttach, Name: "s"})
	readUntil(t, app, nil, proto.TypeStatus)

	sendJSON(t, cli, proto.Message{Type: proto.TypeResize, Cols: 120, Rows: 40})
	status := readUntil(t, app, func(m proto.Message) bool { return m.Cols == 120 }, proto.TypeStatus)
	if status.Rows != 40 {
		t.Fatalf("尺寸不符: %+v", status)
	}
}

func TestExitStatusBroadcast(t *testing.T) {
	srv, _ := startTestServer(t)
	cli := dial(t, wsURL(srv.URL, "/ws/cli"))
	sendJSON(t, cli, proto.Message{Type: proto.TypeHello, Group: "g", Name: "s", Gen: "gen1"})

	app := dial(t, wsURL(srv.URL, "/ws/app?group=g"))
	sendJSON(t, app, proto.Message{Type: proto.TypeAttach, Name: "s"})
	readUntil(t, app, nil, proto.TypeStatus)

	code := 4
	sendJSON(t, cli, proto.Message{Type: proto.TypeExit, Code: &code})
	status := readUntil(t, app, func(m proto.Message) bool { return m.Status == proto.StatusExited }, proto.TypeStatus)
	if status.Code == nil || *status.Code != 4 {
		t.Fatalf("退出码不符: %+v", status)
	}
}

func TestNewAgentGenerationResetsApp(t *testing.T) {
	srv, h := startTestServer(t)
	cli1 := dial(t, wsURL(srv.URL, "/ws/cli"))
	sendJSON(t, cli1, proto.Message{Type: proto.TypeHello, Group: "g", Name: "s", Gen: "gen1"})
	sendBin(t, cli1, "old-output")
	waitBuffered(t, h, "g", "s", "old-output")

	app := dial(t, wsURL(srv.URL, "/ws/app?group=g"))
	sendJSON(t, app, proto.Message{Type: proto.TypeAttach, Name: "s"})
	readUntil(t, app, nil, proto.TypeReset)
	readUntil(t, app, nil, proto.TypeStatus)
	readBinary(t, app)

	// 新 agent 启动：gen 变化，app 应先收到 reset，且不会回放旧缓存。
	cli2 := dial(t, wsURL(srv.URL, "/ws/cli"))
	sendJSON(t, cli2, proto.Message{Type: proto.TypeHello, Group: "g", Name: "s", Gen: "gen2"})
	readUntil(t, app, nil, proto.TypeReset)
	status := readUntil(t, app, nil, proto.TypeStatus)
	if status.Status != proto.StatusOnline {
		t.Fatalf("新 agent 状态应为 online: %+v", status)
	}

	s := h.session("g", "s", false)
	s.mu.Lock()
	buf := string(s.buf)
	s.mu.Unlock()
	if buf != "" {
		t.Fatalf("新 agent 启动后缓存应被清空，实际: %q", buf)
	}
}

func TestCLIDisconnectMarksOffline(t *testing.T) {
	srv, _ := startTestServer(t)
	cli := dial(t, wsURL(srv.URL, "/ws/cli"))
	sendJSON(t, cli, proto.Message{Type: proto.TypeHello, Group: "g", Name: "s", Gen: "gen1"})

	app := dial(t, wsURL(srv.URL, "/ws/app?group=g"))
	sendJSON(t, app, proto.Message{Type: proto.TypeAttach, Name: "s"})
	readUntil(t, app, nil, proto.TypeStatus)

	_ = cli.Close()
	status := readUntil(t, app, func(m proto.Message) bool { return m.Status == proto.StatusOffline }, proto.TypeStatus)
	if status.Name != "s" {
		t.Fatalf("离线状态会话名不符: %+v", status)
	}
}

func TestSessionsListPushedToApp(t *testing.T) {
	srv, _ := startTestServer(t)
	app := dial(t, wsURL(srv.URL, "/ws/app?group=g"))
	readUntil(t, app, func(m proto.Message) bool { return len(m.Sessions) == 0 }, proto.TypeSessions)

	cli := dial(t, wsURL(srv.URL, "/ws/cli"))
	sendJSON(t, cli, proto.Message{Type: proto.TypeHello, Group: "g", Name: "build", Gen: "gen1", Cols: 90, Rows: 28})

	m := readUntil(t, app, func(m proto.Message) bool {
		return len(m.Sessions) == 1 && m.Sessions[0].Name == "build"
	}, proto.TypeSessions)
	info := m.Sessions[0]
	if info.Status != proto.StatusOnline || info.Cols != 90 || info.Rows != 28 {
		t.Fatalf("会话信息不符: %+v", info)
	}
}

func TestAttachUnknownSessionReturnsError(t *testing.T) {
	srv, _ := startTestServer(t)
	app := dial(t, wsURL(srv.URL, "/ws/app?group=g"))
	sendJSON(t, app, proto.Message{Type: proto.TypeAttach, Name: "nope"})
	m := readUntil(t, app, func(m proto.Message) bool { return strings.Contains(m.Err, "nope") }, proto.TypeError)
	if m.Err == "" {
		t.Fatal("应返回错误消息")
	}
}

func TestSecondCLIReplacesFirst(t *testing.T) {
	srv, h := startTestServer(t)
	cli1 := dial(t, wsURL(srv.URL, "/ws/cli"))
	sendJSON(t, cli1, proto.Message{Type: proto.TypeHello, Group: "g", Name: "s", Gen: "gen1"})

	cli2 := dial(t, wsURL(srv.URL, "/ws/cli"))
	sendJSON(t, cli2, proto.Message{Type: proto.TypeHello, Group: "g", Name: "s", Gen: "gen1"})

	// 旧连接应被服务端关闭。
	if err := cli1.SetReadDeadline(time.Now().Add(2 * time.Second)); err != nil {
		t.Fatalf("设置超时失败: %v", err)
	}
	if _, _, err := cli1.ReadMessage(); err == nil {
		t.Fatal("旧 cli 连接应被关闭")
	}

	// 新连接仍可正常上报输出。
	sendBin(t, cli2, "from-new")
	waitBuffered(t, h, "g", "s", "from-new")
}

// app 接入已退出会话时，也应先收到 reset、状态与回放，便于查看退出前输出。
func TestAttachExitedSessionReplays(t *testing.T) {
	srv, h := startTestServer(t)
	cli := dial(t, wsURL(srv.URL, "/ws/cli"))
	sendJSON(t, cli, proto.Message{Type: proto.TypeHello, Group: "g", Name: "s", Gen: "gen1"})
	sendBin(t, cli, "bye\r\n")
	waitBuffered(t, h, "g", "s", "bye")
	code := 0
	sendJSON(t, cli, proto.Message{Type: proto.TypeExit, Code: &code})

	app := dial(t, wsURL(srv.URL, "/ws/app?group=g"))
	sendJSON(t, app, proto.Message{Type: proto.TypeAttach, Name: "s"})
	readUntil(t, app, nil, proto.TypeReset)
	status := readUntil(t, app, func(m proto.Message) bool { return m.Status == proto.StatusExited }, proto.TypeStatus)
	if status.Code == nil || *status.Code != 0 {
		t.Fatalf("退出码不符: %+v", status)
	}
	if got := readBinary(t, app); got != "bye\r\n" {
		t.Fatalf("已退出会话回放不符: %q", got)
	}
}

// 超过上限的缓存应裁剪旧数据且保留最新输出。
func TestAppendOutputTrimsOldData(t *testing.T) {
	buf := make([]byte, bufferLimit)
	for i := range buf {
		buf[i] = byte('a' + i%26)
	}
	buf[bufferLimit-1] = '\n'
	out := appendOutput(buf, []byte("tail\n"))
	if len(out) > bufferLimit {
		t.Fatalf("裁剪后仍超限: %d", len(out))
	}
	if !strings.HasSuffix(string(out), "tail\n") {
		t.Fatal("裁剪后未保留最新输出")
	}
}
