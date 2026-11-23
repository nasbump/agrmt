package main

import (
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/gorilla/websocket"

	"agrmt/internal/proto"
)

// resetSrv 记录每次 /ws/cli 连接收到的 hello 与二进制输出，并可主动断开当前连接。
type resetSrv struct {
	t   *testing.T
	srv *httptest.Server

	conns  chan *websocket.Conn
	bins   chan []byte
	hellos chan proto.Message
}

func newResetSrv(t *testing.T) *resetSrv {
	t.Helper()
	rs := &resetSrv{
		t:      t,
		conns:  make(chan *websocket.Conn, 8),
		bins:   make(chan []byte, 64),
		hellos: make(chan proto.Message, 8),
	}
	mux := http.NewServeMux()
	mux.HandleFunc("/ws/cli", func(w http.ResponseWriter, r *http.Request) {
		ws, err := (&websocket.Upgrader{}).Upgrade(w, r, nil)
		if err != nil {
			t.Errorf("升级失败: %v", err)
			return
		}
		rs.conns <- ws
		for {
			typ, data, err := ws.ReadMessage()
			if err != nil {
				return
			}
			if typ == websocket.BinaryMessage {
				rs.bins <- data
				continue
			}
			m, err := proto.Decode(data)
			if err != nil {
				continue
			}
			if m.Type == proto.TypeHello {
				rs.hellos <- m
			}
		}
	})
	rs.srv = httptest.NewServer(mux)
	t.Cleanup(rs.srv.Close)
	return rs
}

func (rs *resetSrv) nextHello(t *testing.T) proto.Message {
	t.Helper()
	select {
	case m := <-rs.hellos:
		return m
	case <-time.After(5 * time.Second):
		t.Fatal("等待 hello 超时")
		return proto.Message{}
	}
}

func (rs *resetSrv) nextConn(t *testing.T) *websocket.Conn {
	t.Helper()
	select {
	case c := <-rs.conns:
		return c
	case <-time.After(5 * time.Second):
		t.Fatal("等待连接超时")
		return nil
	}
}

// 断开后应自动重连，且沿用同一 gen（同一 agent 仍在运行）。
func TestRemoteReconnectsWithSameGen(t *testing.T) {
	rs := newResetSrv(t)
	r := newRemote(rs.srv.URL, "g", "s", 80, 24)
	r.onInput = func([]byte) {}
	r.start()
	defer r.stop()

	first := rs.nextHello(t)
	if first.Gen == "" {
		t.Fatal("hello 应携带 gen")
	}

	_ = rs.nextConn(t).Close()

	second := rs.nextHello(t)
	if second.Gen != first.Gen {
		t.Fatalf("重连应沿用同一 gen: %s != %s", second.Gen, first.Gen)
	}
}

// 队列超限后应更换 gen，旧积压被丢弃但保留触发超限的最新一帧。
func TestRemoteQueueOverflowRenewsGen(t *testing.T) {
	rs := newResetSrv(t)
	r := newRemote(rs.srv.URL, "g", "s", 80, 24)
	r.onInput = func([]byte) {}
	// 先不启动写入协程，让输出全部堆积在队列里。
	old := r.gen
	chunk := make([]byte, queueLimit+1)
	r.output(chunk)
	if r.gen == old {
		t.Fatal("队列超限后应更换 gen")
	}
	if r.queued != len(chunk) || len(r.queue) != 1 {
		t.Fatalf("超限后应只保留最新一帧，实际排队 %d 帧/%d 字节", len(r.queue), r.queued)
	}

	r.start()
	defer r.stop()
	hello := rs.nextHello(t)
	if hello.Gen != r.gen {
		t.Fatalf("重连 hello 应使用新 gen: %s != %s", hello.Gen, r.gen)
	}
}
