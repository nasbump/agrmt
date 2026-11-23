# agrmt

自托管的 coding-agent 远程管理工具：agent 仍然运行在你自己的电脑上，手机随时查看它的工作进度，并在需要人工决策时直接输入指令。

## 为什么做这个

- 大部分 coding-agent 是基于 TUI 的本地交互，需要人工决策时得一直守在终端前
- 部分 agent 提供远程管理，但要把会话上传到第三方服务器，存在隐私泄漏风险
- agrmt 的三端全部由自己部署，输出只经过自己的 agrmt-srv

## 组成

| 组件 | 平台 | 作用 |
| --- | --- | --- |
| `agrmt-cli` | Linux / macOS | 启动 agent，本地终端原样透传 IO，同时通过 WebSocket 上报输出 |
| `agrmt-srv` | Linux / macOS | 汇总会话，在内存中缓存输出；app 未接入时也不丢内容 |
| `agrmt-app` | Android | 通过 srv 查看会话输出、输入指令，使用 xterm.js 渲染终端 |

```
┌─────────────┐  PTY   ┌────────────┐  WebSocket  ┌────────────┐  WebSocket  ┌───────────┐
│ coding agent│ <----> │ agrmt-cli  │ ----------> │ agrmt-srv  │ ----------> │ agrmt-app │
└─────────────┘        └────────────┘             └────────────┘             └───────────┘
```

cli 与 srv 断线后自动重连；app 接入会话时，srv 依次发送 `reset`、`status` 和全量历史回放，终端能立刻复原当前屏幕与滚动历史。

## 目录结构

```
agrmt/
├── cli/              # agrmt-cli：PTY 透传 + 远程上报
├── srv/              # agrmt-srv：会话管理与内存回放缓存
├── internal/proto/   # 三端共享的 WebSocket 协议
├── docs/prd.md       # 产品需求文档
├── app/              # agrmt-app：Compose + WebView/xterm.js
└── make.sh           # 编译 cli / srv
```

## 编译

### agrmt-cli / agrmt-srv

需要 Go 1.26。

```sh
sh make.sh cli    # 产物: build/agrmt-cli
sh make.sh srv    # 产物: build/agrmt-srv
```

脚本会在编译前执行 `go mod tidy && go mod vendor`。

### agrmt-app

需要 JDK 17 与 Android SDK（compileSdk 34）。首次编译前创建 `app/local.properties` 指向本机 SDK：

```properties
sdk.dir=/opt/platforms/android
```

```sh
cd app
./gradlew :app:assembleDebug    # 产物: app/app/build/outputs/apk/debug/app-debug.apk
```

依赖已缓存时可加 `--offline` 离线编译。

## 运行

### 1. 启动 srv

```sh
./build/agrmt-srv -listen :8989
```

### 2. 启动 cli 与 agent

```sh
./build/agrmt-cli \
  -ws ws://192.168.1.10:8989 \
  -group dev \
  -name codex \
  -io "/usr/bin/codex"
```

| 参数 | 说明 |
| --- | --- |
| `-io` | 必填，agent 路径与启动参数（按 shell 规则拆分） |
| `-ws` | 可选，srv 地址；省略时只在本地终端交互，不上报 |
| `-group` | 指定 `-ws` 时必填，会话分组 |
| `-name` | 指定 `-ws` 时必填，会话名称 |

cli 会以 80x24 为默认尺寸启动 agent，并在本地终端尺寸变化（SIGWINCH）时同步调整 agent 与 srv。

### 3. 使用 app

1. 安装 `app-debug.apk`，填写 srv 地址与分组（地址可省略协议，例如 `192.168.1.10:8989`），保存后点击连接
2. 在会话列表中选择要查看的会话
3. 上方是 agent 输出，下方是提示词输入框和两行快捷按键：方向键，以及 `/`、`Tab`、`Shift+Tab`、`Enter`

终端字号会根据屏幕自动缩放；内容放不下时可以横向/纵向平移查看。横屏下标题栏收紧，输入区移到终端右侧，把高度尽量留给终端。

## 协议摘要

- 文本帧承载 JSON 控制消息，二进制帧承载原始字节流（cli→srv 为 agent 的 PTY 输出，app→srv→cli 为待写入 PTY 的输入）
- cli → srv：`hello`（group/name/gen/cols/rows）、`resize`、`exit`
- app → srv：`attach`、`detach`
- srv → app：`reset`、`status`、`sessions`、`error`
- 每个会话在 srv 内存中保留最近 1 MiB 输出；agent 重新启动（gen 变化）时清空缓存并通知 app 重置终端

## 部署注意

agrmt 面向可信内网设计：srv 使用明文 WebSocket 且不做鉴权，app 允许明文流量。请只在局域网或 VPN 内监听，不要把端口直接暴露到公网。

## 已知限制

- agent 必须是能在 PTY 中运行的交互式程序
- srv 的回放缓存只存在内存中，srv 重启后历史回放会丢失
- 屏幕特别矮（例如横屏高度不足 24 行）时只能平移当前 24 行画面，xterm 的历史回滚列不可见
- 终端行列数由 cli 本地终端决定，app 只负责缩放显示
