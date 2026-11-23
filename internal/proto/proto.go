// Package proto 定义 agrmt-cli、agrmt-srv、agrmt-app 之间共享的 WebSocket 协议。
//
// 约定：
//   - 文本帧承载 JSON 控制消息；
//   - 二进制帧承载原始字节流：cli→srv 为 agent 的 PTY 输出（srv 再转发给 app），
//     app→srv→cli 为待写入 PTY 的输入。
//
// app 端用 xterm.js 直接解析原始输出字节，因此 srv 只需按会话缓存输出流，
// app 接入时先收到 reset，再收到历史回放，即可得到完整屏幕与滚动历史。
package proto

import (
	"encoding/json"
	"fmt"
)

const (
	// cli → srv
	TypeHello  = "hello"  // group、name、gen、cols、rows
	TypeResize = "resize" // cols、rows
	TypeExit   = "exit"   // code

	// app → srv
	TypeAttach = "attach" // name
	TypeDetach = "detach" // name

	// srv → app
	TypeReset    = "reset"    // name
	TypeStatus   = "status"   // name、status、code、cols、rows
	TypeSessions = "sessions" // sessions
	TypeError    = "error"    // err
)

// 会话状态。
const (
	StatusOnline  = "online"  // cli 已连接
	StatusOffline = "offline" // cli 断开
	StatusExited  = "exited"  // agent 进程已退出
)

// Message 是所有控制消息的统一载体，按 type 字段区分语义。
type Message struct {
	Type     string        `json:"type"`
	Group    string        `json:"group,omitempty"`
	Name     string        `json:"name,omitempty"`
	Gen      string        `json:"gen,omitempty"`
	Cols     int           `json:"cols,omitempty"`
	Rows     int           `json:"rows,omitempty"`
	Code     *int          `json:"code,omitempty"`
	Status   string        `json:"status,omitempty"`
	Sessions []SessionInfo `json:"sessions,omitempty"`
	Err      string        `json:"err,omitempty"`
}

func Encode(m Message) ([]byte, error) { return json.Marshal(m) }

func Decode(p []byte) (Message, error) {
	var m Message
	if err := json.Unmarshal(p, &m); err != nil {
		return Message{}, err
	}
	if m.Type == "" {
		return Message{}, fmt.Errorf("proto: 缺少 type 字段")
	}
	return m, nil
}

// SessionInfo 描述一个会话在 app 会话列表中的展示信息。
type SessionInfo struct {
	Name    string `json:"name"`
	Status  string `json:"status"`
	Cols    int    `json:"cols"`
	Rows    int    `json:"rows"`
	Updated int64  `json:"updated"` // Unix 毫秒
}
