package main

import (
	"log"
	"sync"
	"time"

	"github.com/gorilla/websocket"

	"agrmt/internal/proto"
)

const (
	websocketText   = websocket.TextMessage
	websocketBinary = websocket.BinaryMessage
)

// frame 是发往某个 WebSocket 连接的一帧数据。
type frame struct {
	typ  int
	data []byte
}

// conn 包装一个 WebSocket 连接。所有写入都由独立的 writeLoop 串行完成，
// 因为 gorilla/websocket 不允许并发写。
type conn struct {
	ws   *websocket.Conn
	send chan frame
	done chan struct{}
	once sync.Once
}

func newConn(ws *websocket.Conn) *conn {
	return &conn{
		ws:   ws,
		send: make(chan frame, 1024),
		done: make(chan struct{}),
	}
}

// sendFrame 投递一帧；队列满时主动断开连接，让对端重连后重新获取全量内容，
// 避免慢消费者阻塞会话广播。
func (c *conn) sendFrame(typ int, data []byte) {
	select {
	case <-c.done:
		return
	default:
	}
	select {
	case c.send <- frame{typ: typ, data: data}:
	case <-c.done:
	default:
		log.Printf("发送队列已满，主动断开连接")
		c.close()
	}
}

func (c *conn) close() {
	c.once.Do(func() {
		close(c.done)
		_ = c.ws.Close()
	})
}

// writeLoop 串行写出数据并周期性发送 ping 维持连接。
func (c *conn) writeLoop() {
	ticker := time.NewTicker(30 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-c.done:
			return
		case f := <-c.send:
			_ = c.ws.SetWriteDeadline(time.Now().Add(10 * time.Second))
			if err := c.ws.WriteMessage(f.typ, f.data); err != nil {
				c.close()
				return
			}
		case <-ticker.C:
			_ = c.ws.SetWriteDeadline(time.Now().Add(10 * time.Second))
			if err := c.ws.WriteMessage(websocket.PingMessage, nil); err != nil {
				c.close()
				return
			}
		}
	}
}

// sendMessage 发送一条 JSON 控制消息。
func (c *conn) sendMessage(m proto.Message) {
	raw, err := proto.Encode(m)
	if err != nil {
		log.Printf("编码控制消息失败: %v", err)
		return
	}
	c.sendFrame(websocketText, raw)
}

// sendError 向对端报告一条错误信息。
func (c *conn) sendError(msg string) {
	c.sendMessage(proto.Message{Type: proto.TypeError, Err: msg})
}
