package main

import (
	"sort"
	"sync"

	"agrmt/internal/proto"
)

// hub 维护所有 group、会话以及订阅各 group 会话列表的 app 连接。
type hub struct {
	mu       sync.Mutex
	sessions map[string]map[string]*session // group -> name -> session
	apps     map[string]map[*conn]struct{}  // group -> 已连接该组的 app
}

func newHub() *hub {
	return &hub{
		sessions: make(map[string]map[string]*session),
		apps:     make(map[string]map[*conn]struct{}),
	}
}

// session 获取或创建指定会话。
func (h *hub) session(group, name string, create bool) *session {
	h.mu.Lock()
	defer h.mu.Unlock()
	g := h.sessions[group]
	if g == nil {
		if !create {
			return nil
		}
		g = make(map[string]*session)
		h.sessions[group] = g
	}
	s := g[name]
	if s == nil && create {
		s = newSession(h, group, name)
		g[name] = s
	}
	return s
}

// addApp 注册订阅 group 会话列表的 app 连接，并立即推送一次列表。
func (h *hub) addApp(group string, c *conn) {
	h.mu.Lock()
	m := h.apps[group]
	if m == nil {
		m = make(map[*conn]struct{})
		h.apps[group] = m
	}
	m[c] = struct{}{}
	h.mu.Unlock()

	h.notifyGroup(group)
}

// removeApp 注销 app 连接，断开其订阅的所有会话。
func (h *hub) removeApp(group string, c *conn) {
	h.mu.Lock()
	if m := h.apps[group]; m != nil {
		delete(m, c)
		if len(m) == 0 {
			delete(h.apps, group)
		}
	}
	var all []*session
	for _, g := range h.sessions {
		for _, s := range g {
			all = append(all, s)
		}
	}
	h.mu.Unlock()

	for _, s := range all {
		s.detachApp(c)
	}
}

// removeCLI 在 cli 连接断开时把对应会话置为离线。
func (h *hub) removeCLI(group, name string, c *conn) {
	if s := h.session(group, name, false); s != nil {
		s.detachCLI(c)
	}
}

// notifyGroup 向订阅该 group 的所有 app 推送最新会话列表。
// 先取快照再逐个加锁，避免与 session 内部持锁调用 hub 的路径形成环路。
func (h *hub) notifyGroup(group string) {
	h.mu.Lock()
	var sessions []*session
	for _, s := range h.sessions[group] {
		sessions = append(sessions, s)
	}
	apps := make([]*conn, 0, len(h.apps[group]))
	for c := range h.apps[group] {
		apps = append(apps, c)
	}
	h.mu.Unlock()

	if len(apps) == 0 {
		return
	}

	infos := make([]proto.SessionInfo, 0, len(sessions))
	for _, s := range sessions {
		s.mu.Lock()
		infos = append(infos, s.infoLocked())
		s.mu.Unlock()
	}
	sort.Slice(infos, func(i, j int) bool { return infos[i].Name < infos[j].Name })

	msg := proto.Message{Type: proto.TypeSessions, Group: group, Sessions: infos}
	for _, c := range apps {
		c.sendMessage(msg)
	}
}
