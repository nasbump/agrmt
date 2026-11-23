# agrmt - agent remote manager

## 需求背景
- 当前大部分coding-agent 为基于tui的本地交互，当需要人工决策或者干预时，必须有人盯着
- 部分coding-agent 支持远程管理，但需要将session上传至第三方服务器，有隐私泄漏风险

## 需求概览
开发一套agent远程管理系统，能让人工随时查看agent工作进度，及与agent交互；该系统一共包含三个端：
- 本地端：agrmt-cli, 用于启动agent，并将agent的IO原样输出到本地的stdin/out，同时复制一份通过websocket的方式push至srv端
- srv端：agrmt-srv, 与cli端有私有交互协议
- android端：agrmt-app, 通过agrmt-srv，远程与agent进行IO交互

## 我设想的产品形态
### agrmt-cli
- 技术栈：golang, GOROOT=/home/wupin/goenv/goroot,GOPATH=/home/wupin/goenv/gopath
- 启动： agrmt-cli -ws <srv-addr> -group <group-code> -name <session-name> -io "<path-to-agent> [agent-options]"
- 参数说明：
  - ws: 可选，用于是否启动远程管理，未指定时表示只按既定方式启动agent，即仅本地交互
  - group: ws指定时则必须传入，用于会话分组
  - name: ws指定时则必须传入，用于区分会话
  - io: 必须，agent的路径及agent的启动参数
- 每次启动一个会话，都需要将session中所有的输出push到srv

### agrmt-srv
- 技术栈：golang, GOROOT=/home/wupin/goenv/goroot,GOPATH=/home/wupin/goenv/gopath
- 启动：agrmt-srv -listen <listen-addr>
- 对于连上来的session，若没有app访问时，则直接将消息缓存在内存中

### agrtm-app
- 目前只需要实现android端
- android-ndk: /opt/platforms/android/android-ndk-r27c
- 首次启动时，需要配置srv-addr及group-code，并保存到手机
- 非首次启动时，需要展示srv-addr,group-code列表，由用户选择并连接
- 连接srv后，展示该group-code下所有会话names，由用户选择并打开
- 打开session后，窗口分为上下两部分，上部分为agent的输出内容，下部分占窗口20%，用于输入提示词；同时展示常用快捷操作:方向上下左右、斜杠/、tab键、shift+tab键、回车键

