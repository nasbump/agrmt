package main

import (
	"flag"
	"log"
	"net/http"

	"github.com/gorilla/websocket"

	"agrmt/internal/proto"
)

var upgrader = websocket.Upgrader{
	ReadBufferSize:  32 * 1024,
	WriteBufferSize: 32 * 1024,
	// 供局域网内的手机直连，不做来源校验。
	CheckOrigin: func(*http.Request) bool { return true },
}

func main() {
	listen := flag.String("listen", ":8989", "监听地址，例如 :8989")
	flag.Parse()

	log.Printf("agrmt-srv 监听 %s", *listen)
	if err := http.ListenAndServe(*listen, newMux(newHub())); err != nil {
		log.Fatalf("启动失败: %v", err)
	}
}

// newMux 组装 HTTP 路由，便于测试复用。
func newMux(h *hub) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/ws/cli", func(w http.ResponseWriter, r *http.Request) {
		serveCLI(h, w, r)
	})
	mux.HandleFunc("/ws/app", func(w http.ResponseWriter, r *http.Request) {
		serveApp(h, w, r)
	})
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte("ok"))
	})
	return mux
}

// serveCLI 处理 agrmt-cli 的连接：首个文本帧必须是 hello。
func serveCLI(h *hub, w http.ResponseWriter, r *http.Request) {
	ws, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		log.Printf("cli 升级 WebSocket 失败: %v", err)
		return
	}
	c := newConn(ws)
	go c.writeLoop()
	defer c.close()

	var (
		sess  *session
		group string
		name  string
	)

	handle := func(m proto.Message) {
		switch m.Type {
		case proto.TypeHello:
			if m.Group == "" || m.Name == "" || m.Gen == "" {
				c.sendError("hello 缺少 group/name/gen")
				c.close()
				return
			}
			group, name = m.Group, m.Name
			sess = h.session(group, name, true)
			sess.attachCLI(c, m)
		case proto.TypeResize:
			if sess != nil {
				sess.resizeFromCLI(m.Cols, m.Rows)
			}
		case proto.TypeExit:
			if sess != nil {
				sess.exit(m.Code)
			}
		}
	}

	for {
		typ, data, err := ws.ReadMessage()
		if err != nil {
			break
		}
		if typ == websocket.BinaryMessage {
			if sess != nil {
				sess.output(data)
			}
			continue
		}
		m, err := proto.Decode(data)
		if err != nil {
			c.sendError(err.Error())
			continue
		}
		if sess == nil && m.Type != proto.TypeHello {
			c.sendError("必须先发送 hello")
			continue
		}
		handle(m)
		if sess == nil {
			// hello 被拒绝，连接已关闭。
			return
		}
	}

	if sess != nil {
		h.removeCLI(group, name, c)
	}
}

// serveApp 处理 agrmt-app 的连接，group 由查询参数指定。
func serveApp(h *hub, w http.ResponseWriter, r *http.Request) {
	group := r.URL.Query().Get("group")
	if group == "" {
		http.Error(w, "缺少 group 查询参数", http.StatusBadRequest)
		return
	}

	ws, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		log.Printf("app 升级 WebSocket 失败: %v", err)
		return
	}
	c := newConn(ws)
	go c.writeLoop()
	defer c.close()
	defer h.removeApp(group, c)

	h.addApp(group, c)

	var attached *session
	for {
		typ, data, err := ws.ReadMessage()
		if err != nil {
			break
		}
		if typ == websocket.BinaryMessage {
			if attached != nil {
				attached.input(data)
			}
			continue
		}
		m, err := proto.Decode(data)
		if err != nil {
			c.sendError(err.Error())
			continue
		}
		switch m.Type {
		case proto.TypeAttach:
			s := h.session(group, m.Name, false)
			if s == nil {
				c.sendError("会话不存在: " + m.Name)
				continue
			}
			if attached != nil && attached != s {
				attached.detachApp(c)
			}
			attached = s
			s.attachApp(c)
		case proto.TypeDetach:
			if attached != nil {
				attached.detachApp(c)
				attached = nil
			}
		}
	}
}
