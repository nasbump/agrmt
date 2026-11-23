package main

import (
	"bytes"
	"sync"
	"time"

	"agrmt/internal/proto"
)

// bufferLimit 是单会话缓存的原始 PTY 输出上限（字节）。
// 超出后保留最新一半，并尽量对齐到换行边界，避免 app 回放时从半个
// 转义序列或半个 UTF-8 字符开始。
const bufferLimit = 1 << 20

// session 代表一个 (group, name) 会话。cli 断开后会话仍保留在内存中，
// 便于 app 稍后接入时回放历史输出。
type session struct {
	hub *hub

	mu     sync.Mutex
	group  string
	name   string
	gen    string // 每次 agent 新启动（cli 重新拉起）时变化
	status string
	cols   int
	rows   int
	code   *int
	buf    []byte
	cli    *conn
	apps   map[*conn]struct{}
	updMs  int64
}

func newSession(h *hub, group, name string) *session {
	return &session{
		hub:    h,
		group:  group,
		name:   name,
		status: proto.StatusOffline,
		cols:   80,
		rows:   24,
		apps:   make(map[*conn]struct{}),
		updMs:  time.Now().UnixMilli(),
	}
}

func (s *session) touchLocked() { s.updMs = time.Now().UnixMilli() }

func (s *session) infoLocked() proto.SessionInfo {
	return proto.SessionInfo{
		Name:    s.name,
		Status:  s.status,
		Cols:    s.cols,
		Rows:    s.rows,
		Updated: s.updMs,
	}
}

func (s *session) statusMessageLocked() proto.Message {
	return proto.Message{
		Type:   proto.TypeStatus,
		Name:   s.name,
		Status: s.status,
		Code:   s.code,
		Cols:   s.cols,
		Rows:   s.rows,
	}
}

// appendOutput 追加输出并在超限时裁掉旧数据。
func appendOutput(buf, p []byte) []byte {
	buf = append(buf, p...)
	if len(buf) <= bufferLimit {
		return buf
	}
	cut := len(buf) - bufferLimit/2
	if idx := bytes.IndexByte(buf[cut:], '\n'); idx >= 0 {
		cut += idx + 1
	}
	return append([]byte(nil), buf[cut:]...)
}

// 以下所有对 app / cli 的入队操作都在持有 s.mu 期间完成，
// 以保证「reset → 回放 → 实时输出」在单连接内严格有序。

// attachCLI 注册（或替换）会话的 cli 连接。gen 变化表示 agent 重新启动，
// 需要清空旧缓存并让已接入的 app 重置终端。
func (s *session) attachCLI(c *conn, m proto.Message) {
	s.mu.Lock()
	old := s.cli
	newRun := s.gen != m.Gen
	s.cli = c
	s.gen = m.Gen
	s.status = proto.StatusOnline
	s.code = nil
	if m.Cols > 0 {
		s.cols = m.Cols
	}
	if m.Rows > 0 {
		s.rows = m.Rows
	}
	if newRun {
		s.buf = nil
		reset := proto.Message{Type: proto.TypeReset, Name: s.name}
		for a := range s.apps {
			a.sendMessage(reset)
		}
	}
	s.touchLocked()
	status := s.statusMessageLocked()
	for a := range s.apps {
		a.sendMessage(status)
	}
	s.mu.Unlock()

	if old != nil && old != c {
		old.close()
	}
	s.hub.notifyGroup(s.group)
}

// detachCLI 在 cli 读循环结束时调用；若它仍是当前连接则把会话置为离线。
func (s *session) detachCLI(c *conn) {
	s.mu.Lock()
	if s.cli != c {
		s.mu.Unlock()
		return
	}
	s.cli = nil
	if s.status == proto.StatusOnline {
		s.status = proto.StatusOffline
	}
	s.touchLocked()
	status := s.statusMessageLocked()
	for a := range s.apps {
		a.sendMessage(status)
	}
	s.mu.Unlock()

	s.hub.notifyGroup(s.group)
}

// output 缓存 cli 上报的 PTY 原始输出并广播给已接入的 app。
func (s *session) output(p []byte) {
	s.mu.Lock()
	s.buf = appendOutput(s.buf, p)
	s.touchLocked()
	for a := range s.apps {
		a.sendFrame(websocketBinary, p)
	}
	s.mu.Unlock()
}

// resizeFromCLI 更新会话尺寸（cli 本地终端变化）。
func (s *session) resizeFromCLI(cols, rows int) {
	if cols <= 0 || rows <= 0 {
		return
	}
	s.mu.Lock()
	s.cols = cols
	s.rows = rows
	s.touchLocked()
	status := s.statusMessageLocked()
	for a := range s.apps {
		a.sendMessage(status)
	}
	s.mu.Unlock()

	s.hub.notifyGroup(s.group)
}

// exit 记录 agent 退出并断开 cli。
func (s *session) exit(code *int) {
	s.mu.Lock()
	s.status = proto.StatusExited
	s.code = code
	cli := s.cli
	s.cli = nil
	s.touchLocked()
	status := s.statusMessageLocked()
	for a := range s.apps {
		a.sendMessage(status)
	}
	s.mu.Unlock()

	if cli != nil {
		cli.close()
	}
	s.hub.notifyGroup(s.group)
}

// attachApp 让 app 接入会话：入队 reset、当前状态、全量回放。
// 状态先于回放下发，app 可先按会话尺寸调整本地终端，再写入历史输出。
func (s *session) attachApp(c *conn) {
	s.mu.Lock()
	s.apps[c] = struct{}{}
	c.sendMessage(proto.Message{Type: proto.TypeReset, Name: s.name})
	c.sendMessage(s.statusMessageLocked())
	if len(s.buf) > 0 {
		c.sendFrame(websocketBinary, append([]byte(nil), s.buf...))
	}
	s.mu.Unlock()
}

func (s *session) detachApp(c *conn) {
	s.mu.Lock()
	delete(s.apps, c)
	s.mu.Unlock()
}

// input 把 app 的原始输入字节转发给 cli。
func (s *session) input(p []byte) {
	s.mu.Lock()
	cli := s.cli
	s.mu.Unlock()
	if cli == nil {
		return
	}
	cli.sendFrame(websocketBinary, p)
}
