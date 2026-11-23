package main

import (
	"errors"
	"io"
	"os"
	"os/exec"
	"syscall"

	"github.com/creack/pty"
	"golang.org/x/term"
)

// openAgent 在 PTY 中启动 agent，使 agent 认为自己在终端里运行，
// 同时让 cli 能截获它的全部输出。
func openAgent(argv []string, cols, rows int) (*os.File, *exec.Cmd, error) {
	if len(argv) == 0 {
		return nil, nil, errors.New("空命令")
	}
	cmd := exec.Command(argv[0], argv[1:]...)
	cmd.Env = append(os.Environ(), "TERM=xterm-256color")

	winSize := &pty.Winsize{Cols: uint16(cols), Rows: uint16(rows)}
	ptmx, err := pty.StartWithSize(cmd, winSize)
	if err != nil {
		return nil, nil, err
	}
	return ptmx, cmd, nil
}

// copyOutput 把 PTY 输出写到本地 stdout，并上报给 srv。
// PTY 关闭后读取返回 EIO，视为正常结束。
func copyOutput(ptmx *os.File, stdout, stderr io.Writer, remote *remote) {
	buf := make([]byte, 32*1024)
	for {
		n, err := ptmx.Read(buf)
		if n > 0 {
			_, _ = stdout.Write(buf[:n])
			if remote != nil {
				remote.output(buf[:n])
			}
		}
		if err != nil {
			if !errors.Is(err, io.EOF) && !errors.Is(err, syscall.EIO) {
				_, _ = io.WriteString(stderr, "agrmt-cli: 读取 agent 输出失败: "+err.Error()+"\n")
			}
			return
		}
	}
}

// copyInput 把本地 stdin 原样写入 PTY，stdin 关闭后返回。
func copyInput(ptmx *os.File, stdin io.Reader) {
	buf := make([]byte, 4096)
	for {
		n, err := stdin.Read(buf)
		if n > 0 {
			if _, werr := ptmx.Write(buf[:n]); werr != nil {
				return
			}
		}
		if err != nil {
			return
		}
	}
}

// resizeAgent 更新 agent PTY 的窗口尺寸。
func resizeAgent(ptmx *os.File, cols, rows int) error {
	if cols <= 0 || rows <= 0 {
		return nil
	}
	return pty.Setsize(ptmx, &pty.Winsize{Cols: uint16(cols), Rows: uint16(rows)})
}

// waitAgent 等待 agent 退出并返回退出码；被信号终止时按 shell 惯例返回 128+信号。
func waitAgent(cmd *exec.Cmd) int {
	err := cmd.Wait()
	if err == nil {
		return 0
	}
	var ee *exec.ExitError
	if errors.As(err, &ee) {
		if status, ok := ee.Sys().(syscall.WaitStatus); ok && status.Signaled() {
			return 128 + int(status.Signal())
		}
		return ee.ExitCode()
	}
	return 1
}

// rawMode 让本地终端进入原始模式，按键不再被本地行编辑拦截。
func rawMode(fd int, isFile bool) (*term.State, bool) {
	if !isFile || !term.IsTerminal(fd) {
		return nil, false
	}
	state, err := term.MakeRaw(fd)
	if err != nil {
		return nil, false
	}
	return state, true
}

func restoreMode(fd int, state *term.State, ok bool) {
	if ok && state != nil {
		_ = term.Restore(fd, state)
	}
}
