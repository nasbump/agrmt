package main

import (
	"log"
	"net/http"
	"time"
)

// connLog 记录一条连接的生命周期，用于输出接入/断开事件日志。
// 每条连接只由处理它的协程访问，无需加锁。
// cli 的 group 与会话名要等到 hello 才确定，因此接入日志延迟到身份明确后打印；
// 若连接在 hello 之前就断开，断开日志里会补打一条接入记录，避免丢失来源信息。
type connLog struct {
	appType string
	remote  string
	group   string
	session string
	start   time.Time
	logged  bool
}

func newConnLog(appType string, r *http.Request, group string) *connLog {
	return &connLog{
		appType: appType,
		// 保留端口：同一来源（尤其是 NAT 后）可能有多条连接，端口用于区分。
		remote: r.RemoteAddr,
		group:  group,
		start:  time.Now(),
	}
}

// identify 更新身份信息：cli 在 hello 后调用，app 在 attach 后补充会话名。
func (c *connLog) identify(group, session string) {
	if group != "" {
		c.group = group
	}
	if session != "" {
		c.session = session
	}
}

// connected 打印接入日志，重复调用无效。
func (c *connLog) connected() {
	if c.logged {
		return
	}
	c.logged = true
	log.Printf("[连接接入] apptype=%s addr=%s group=%s session=%s",
		c.appType, c.remote, orDash(c.group), orDash(c.session))
}

// sessionAttached 在 app 接入会话时打印：会话名只有 attach 之后才确定，
// 连接接入日志里先以 "-" 占位，这里补齐。
func (c *connLog) sessionAttached() {
	log.Printf("[会话接入] apptype=%s addr=%s group=%s session=%s",
		c.appType, c.remote, orDash(c.group), orDash(c.session))
}

// closed 打印断开日志并带上连接存活时间；未打印过接入日志的连接在此补上。
func (c *connLog) closed() {
	c.connected()
	log.Printf("[连接断开] apptype=%s addr=%s group=%s session=%s 存活=%s",
		c.appType, c.remote, orDash(c.group), orDash(c.session),
		time.Since(c.start).Round(time.Second))
}

func orDash(s string) string {
	if s == "" {
		return "-"
	}
	return s
}
