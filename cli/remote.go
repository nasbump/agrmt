package main

import (
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"log"
	"net/url"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"

	"agrmt/internal/proto"
)

const (
	// queueLimit 是等待发送的帧字节上限；超出说明网络长期拥塞或断线过久，
	// 此时丢弃待发送内容并更换 gen，让 srv/app 重置终端，保证显示最终一致。
	queueLimit = 8 << 20
)

type frame struct {
	typ  int
	data []byte
}

// remote 维护与 agrmt-srv 的连接：断线自动重连、期间暂存输出，
// 并把 app 端发来的输入/尺寸请求交给本地回调。
type remote struct {
	addr  string
	group string
	name  string
	gen   string
	cols  int
	rows  int

	// onInput 把 app 端输入写入本地 PTY。
	onInput func([]byte)

	mu       sync.Mutex
	queue    []frame
	queued   int
	conn     *websocket.Conn
	exitCode *int

	notify chan struct{}
	stopCh chan struct{}
	once   sync.Once
	done   chan struct{}
}

func newRemote(addr, group, name string, cols, rows int) *remote {
	return &remote{
		addr:   addr,
		group:  group,
		name:   name,
		gen:    newRunID(),
		cols:   cols,
		rows:   rows,
		notify: make(chan struct{}, 1),
		stopCh: make(chan struct{}),
		done:   make(chan struct{}),
	}
}

func (r *remote) start() { go r.run() }

// wake 非阻塞地唤醒写入协程。
func (r *remote) wake() {
	select {
	case r.notify <- struct{}{}:
	default:
	}
}

// enqueue 把一帧放入待发送队列；队列超限时丢弃积压并更换 gen。
func (r *remote) enqueue(f frame) {
	r.mu.Lock()
	if r.queued+len(f.data) > queueLimit {
		log.Printf("发送队列超限，丢弃积压并以新会话恢复")
		r.queue = nil
		r.queued = 0
		r.gen = newRunID()
		if r.conn != nil {
			_ = r.conn.Close()
		}
	}
	r.queue = append(r.queue, f)
	r.queued += len(f.data)
	r.mu.Unlock()
	r.wake()
}

// output 上报 agent 的原始输出。
func (r *remote) output(p []byte) {
	r.enqueue(frame{typ: websocket.BinaryMessage, data: append([]byte(nil), p...)})
}

// resize 上报本地终端尺寸变化。
func (r *remote) resize(cols, rows int) {
	if cols <= 0 || rows <= 0 {
		return
	}
	raw, err := proto.Encode(proto.Message{Type: proto.TypeResize, Cols: cols, Rows: rows})
	if err != nil {
		return
	}
	r.enqueue(frame{typ: websocket.TextMessage, data: raw})
}

// exited 记录 agent 退出码，写入协程在队列清空后发送 exit 并结束。
func (r *remote) exited(code int) {
	r.mu.Lock()
	c := code
	r.exitCode = &c
	r.mu.Unlock()
	r.wake()
}

// stop 关闭远程连接；可重复调用。若 agent 已退出则等待剩余数据与 exit 消息发出。
func (r *remote) stop() {
	r.once.Do(func() {
		close(r.stopCh)
		r.wake()
	})
	select {
	case <-r.done:
	case <-time.After(3 * time.Second):
		r.mu.Lock()
		ws := r.conn
		r.mu.Unlock()
		if ws != nil {
			_ = ws.Close()
		}
		<-r.done
	}
}

// run 是唯一的写入协程：负责连接建立、发送队列与退出消息，断线自动重连。
func (r *remote) run() {
	defer close(r.done)
	backoff := time.Second
	for {
		select {
		case <-r.stopCh:
			return
		default:
		}

		ws, err := r.dial()
		if err != nil {
			r.mu.Lock()
			exiting := r.exitCode != nil
			r.mu.Unlock()
			if exiting {
				log.Printf("无法连接 agrmt-srv，退出消息未能送达: %v", err)
				return
			}
			log.Printf("连接 agrmt-srv 失败: %v（%s 后重试）", err, backoff)
			select {
			case <-time.After(backoff):
			case <-r.notify:
			case <-r.stopCh:
			}
			if backoff < 10*time.Second {
				backoff *= 2
			}
			continue
		}
		backoff = time.Second
		if err := r.serve(ws); err != nil {
			log.Printf("与 agrmt-srv 的连接断开: %v", err)
		}
		r.mu.Lock()
		exitCode := r.exitCode
		r.mu.Unlock()
		if exitCode != nil {
			// 已尝试发送 exit，结束。
			return
		}
	}
}

func (r *remote) dial() (*websocket.Conn, error) {
	u, err := normalizeURL(r.addr)
	if err != nil {
		return nil, err
	}
	d := websocket.Dialer{
		HandshakeTimeout: 10 * time.Second,
		ReadBufferSize:   32 * 1024,
		WriteBufferSize:  32 * 1024,
	}
	ws, _, err := d.Dial(u, nil)
	return ws, err
}

// normalizeURL 允许 srv 地址使用 ws/wss/http/https 或省略协议。
func normalizeURL(addr string) (string, error) {
	// 省略协议时 url.Parse 会把 host:port 误判为 scheme，先补默认协议。
	if !strings.Contains(addr, "://") {
		addr = "ws://" + addr
	}
	u, err := url.Parse(addr)
	if err != nil {
		return "", err
	}
	switch u.Scheme {
	case "ws", "wss":
	case "http":
		u.Scheme = "ws"
	case "https":
		u.Scheme = "wss"
	default:
		return "", fmt.Errorf("不支持的地址协议: %s", u.Scheme)
	}
	if u.Host == "" {
		return "", fmt.Errorf("地址缺少主机部分: %s", addr)
	}
	u.Path = "/ws/cli"
	u.RawQuery = ""
	return u.String(), nil
}

// serve 完成一次连接的握手与收发，直到连接不可用。
func (r *remote) serve(ws *websocket.Conn) error {
	defer ws.Close()

	r.mu.Lock()
	hello := proto.Message{
		Type: proto.TypeHello, Group: r.group, Name: r.name, Gen: r.gen,
		Cols: r.cols, Rows: r.rows,
	}
	raw, err := proto.Encode(hello)
	r.conn = ws
	r.mu.Unlock()
	if err != nil {
		return err
	}

	_ = ws.SetWriteDeadline(time.Now().Add(10 * time.Second))
	if err := ws.WriteMessage(websocket.TextMessage, raw); err != nil {
		return err
	}

	errCh := make(chan error, 1)
	go r.readLoop(ws, errCh)

	ticker := time.NewTicker(30 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case err := <-errCh:
			r.disconnect(ws)
			return err
		case <-ticker.C:
			_ = ws.SetWriteDeadline(time.Now().Add(10 * time.Second))
			if err := ws.WriteMessage(websocket.PingMessage, nil); err != nil {
				r.disconnect(ws)
				return err
			}
		case <-r.notify:
		}

		// 尽力把队列写空。
		for {
			r.mu.Lock()
			if len(r.queue) == 0 {
				exitCode := r.exitCode
				r.mu.Unlock()
				if exitCode != nil {
					raw, _ := proto.Encode(proto.Message{Type: proto.TypeExit, Code: exitCode})
					_ = ws.SetWriteDeadline(time.Now().Add(5 * time.Second))
					_ = ws.WriteMessage(websocket.TextMessage, raw)
					r.disconnect(ws)
					return nil
				}
				break
			}
			f := r.queue[0]
			r.queue = r.queue[1:]
			r.queued -= len(f.data)
			r.mu.Unlock()

			_ = ws.SetWriteDeadline(time.Now().Add(30 * time.Second))
			if err := ws.WriteMessage(f.typ, f.data); err != nil {
				r.mu.Lock()
				r.queue = append([]frame{f}, r.queue...)
				r.queued += len(f.data)
				r.mu.Unlock()
				r.disconnect(ws)
				return err
			}
		}
	}
}

// readLoop 接收 srv 下发的控制消息与 app 输入。
func (r *remote) readLoop(ws *websocket.Conn, errCh chan<- error) {
	for {
		typ, data, err := ws.ReadMessage()
		if err != nil {
			errCh <- err
			return
		}
		if typ == websocket.BinaryMessage {
			if r.onInput != nil {
				r.onInput(data)
			}
			continue
		}
		m, err := proto.Decode(data)
		if err != nil {
			continue
		}
		switch m.Type {
		case proto.TypeError:
			log.Printf("agrmt-srv 返回错误: %s", m.Err)
		}
	}
}

// disconnect 清理已断开的连接状态。
func (r *remote) disconnect(ws *websocket.Conn) {
	r.mu.Lock()
	if r.conn == ws {
		r.conn = nil
	}
	r.mu.Unlock()
	_ = ws.Close()
}

// newRunID 为每次 agent 启动生成唯一标识，srv 据此识别新的 agent 进程。
func newRunID() string {
	b := make([]byte, 8)
	if _, err := rand.Read(b); err != nil {
		return fmt.Sprintf("%d", os.Getpid())
	}
	return hex.EncodeToString(b)
}
