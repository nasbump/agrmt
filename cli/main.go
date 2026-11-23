package main

import (
	"flag"
	"fmt"
	"io"
	"os"
	"os/signal"
	"sync"
	"syscall"

	"github.com/google/shlex"
	"golang.org/x/term"
)

func main() {
	wsAddr := flag.String("ws", "", "agrmt-srv 地址，例如 ws://127.0.0.1:8989；不指定则仅本地交互")
	group := flag.String("group", "", "会话分组（指定 -ws 时必填）")
	name := flag.String("name", "", "会话名称（指定 -ws 时必填）")
	ioSpec := flag.String("io", "", "agent 路径及启动参数，例如 \"/usr/bin/codex --foo\"")
	flag.Parse()

	if *ioSpec == "" {
		fmt.Fprintln(os.Stderr, "agrmt-cli: -io 为必填参数")
		flag.Usage()
		os.Exit(2)
	}
	if *wsAddr != "" && (*group == "" || *name == "") {
		fmt.Fprintln(os.Stderr, "agrmt-cli: 指定 -ws 时 -group 与 -name 均为必填")
		flag.Usage()
		os.Exit(2)
	}

	argv, err := shlex.Split(*ioSpec)
	if err != nil || len(argv) == 0 {
		fmt.Fprintf(os.Stderr, "agrmt-cli: 解析 -io 失败: %v\n", err)
		os.Exit(2)
	}

	os.Exit(run(options{
		argv:   argv,
		wsAddr: *wsAddr,
		group:  *group,
		name:   *name,
		stdin:  os.Stdin,
		stdout: os.Stdout,
		stderr: os.Stderr,
	}))
}

// options 汇总一次运行所需的输入，便于测试注入。
type options struct {
	argv   []string
	wsAddr string
	group  string
	name   string
	stdin  io.Reader
	stdout io.Writer
	stderr io.Writer
}

// run 启动 agent，完成本地 IO 透传与可选的远程上报，返回进程退出码。
func run(opts options) int {
	cols, rows := termSize(opts.stdin, opts.stdout)
	ptmx, cmd, err := openAgent(opts.argv, cols, rows)
	if err != nil {
		fmt.Fprintf(opts.stderr, "agrmt-cli: 启动 agent 失败: %v\n", err)
		return 1
	}
	defer ptmx.Close()

	var remote *remote
	if opts.wsAddr != "" {
		remote = newRemote(opts.wsAddr, opts.group, opts.name, cols, rows)
		// app 端输入的字节写入本地 PTY，等价于在本地终端敲键。
		remote.onInput = func(p []byte) {
			_, _ = ptmx.Write(p)
		}
		remote.start()
	}

	// 进入原始模式，让按键原样交给 agent；从管道启动时跳过。
	stdinFd, stdinIsFile := fileFd(opts.stdin)
	oldState, isTTY := rawMode(stdinFd, stdinIsFile)
	defer restoreMode(stdinFd, oldState, isTTY)

	stop := make(chan struct{})

	// 输出协程负责把 agent 的剩余输出读完；输入协程可能永久阻塞在
	// 本地 stdin 上，进程即将退出，不等待它。
	var outWG sync.WaitGroup
	outWG.Add(1)
	go func() {
		defer outWG.Done()
		copyOutput(ptmx, opts.stdout, opts.stderr, remote)
	}()
	go copyInput(ptmx, opts.stdin)

	// 终端尺寸变化时同步调整 agent，并把新尺寸上报给 srv。
	if isTTY {
		sig := make(chan os.Signal, 1)
		signal.Notify(sig, syscall.SIGWINCH)
		defer signal.Stop(sig)
		outWG.Add(1)
		go func() {
			defer outWG.Done()
			for {
				select {
				case <-stop:
					return
				case <-sig:
					c, r := termSize(opts.stdin, opts.stdout)
					_ = resizeAgent(ptmx, c, r)
					if remote != nil {
						remote.resize(c, r)
					}
				}
			}
		}()
	}

	code := waitAgent(cmd)
	close(stop)
	outWG.Wait()

	if remote != nil {
		remote.exited(code)
		remote.stop()
	}
	return code
}

// fileFd 返回底层文件描述符；非 *os.File 时返回 false。
func fileFd(v any) (int, bool) {
	f, ok := v.(*os.File)
	if !ok {
		return -1, false
	}
	return int(f.Fd()), true
}

// termSize 返回本地终端尺寸，无法获取时回退到 80x24。
func termSize(r io.Reader, w io.Writer) (int, int) {
	var fds []int
	if fd, ok := fileFd(w); ok {
		fds = append(fds, fd)
	}
	if fd, ok := fileFd(r); ok {
		fds = append(fds, fd)
	}
	for _, fd := range fds {
		if cols, rows, err := term.GetSize(fd); err == nil && cols > 0 && rows > 0 {
			return cols, rows
		}
	}
	return 80, 24
}
