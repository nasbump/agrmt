package com.agrmt.app.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.agrmt.app.data.ConfigStore
import com.agrmt.app.data.ServerConfig
import com.agrmt.app.model.Message
import com.agrmt.app.model.MsgType
import com.agrmt.app.model.SessionInfo
import com.agrmt.app.model.SessionStatus
import com.agrmt.app.net.AgrmtClient
import com.agrmt.app.term.TerminalController

/** 界面所处的阶段。 */
enum class Stage { SERVERS, SESSIONS, TERMINAL }

/** 主界面状态与 srv 连接的唯一管理者。 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val store = ConfigStore(app)
    private var client: AgrmtClient? = null
    private var active: ServerConfig? = null

    val terminal = TerminalController()

    var stage by mutableStateOf(Stage.SERVERS)
        private set
    var configs by mutableStateOf(store.load())
        private set
    var sessions by mutableStateOf<List<SessionInfo>>(emptyList())
        private set
    var connected by mutableStateOf(false)
        private set
    var attachedName by mutableStateOf<String?>(null)
        private set
    var sessionStatus by mutableStateOf<String?>(null)
        private set
    var exitCode by mutableStateOf<Int?>(null)
        private set
    var notice by mutableStateOf<String?>(null)
        private set

    private var appCursorKeys = false

    // 记录上次 attach 时的页面就绪代次，用于识别 WebView 重建后需要重新接入。
    private var attachGen = -1

    init {
        terminal.onCursorKeys = { appCursorKeys = it }
        terminal.onInput = { bytes -> client?.sendInput(bytes) }
        // WebView 重建后页面会重新加载：重新 attach 让 srv 重发 reset 与全量回放，
        // 否则终端会停在空白状态。
        terminal.onReady = {
            val name = attachedName
            if (name != null && attachGen != terminal.readyGen) {
                terminal.reset()
                attachGen = terminal.readyGen
                client?.attach(name)
            }
        }
    }

    /** 记录并保存一组服务器配置。 */
    fun saveConfig(addr: String, group: String): Boolean {
        val a = addr.trim()
        val g = group.trim()
        if (a.isEmpty() || g.isEmpty()) {
            notice = "srv 地址与分组不能为空"
            return false
        }
        configs = store.addOrReplace(ServerConfig(a, g))
        return true
    }

    fun removeConfig(cfg: ServerConfig) {
        configs = store.remove(cfg)
    }

    /** 连接指定的 srv；成功后自动进入会话列表。 */
    fun connect(cfg: ServerConfig) {
        disconnect()
        notice = null
        sessions = emptyList()
        attachedName = null
        sessionStatus = null
        exitCode = null

        val c = AgrmtClient(object : AgrmtClient.Listener {
            override fun onOpen() {
                connected = true
                stage = Stage.SESSIONS
            }

            override fun onMessage(m: Message) = handleMessage(m)

            override fun onOutput(bytes: ByteArray) {
                terminal.append(bytes)
            }

            override fun onClosed(reason: String) {
                connected = false
                notice = "连接已断开: $reason"
                if (attachedName != null) {
                    attachedName = null
                    stage = Stage.SESSIONS
                }
            }
        })
        client = c
        active = cfg
        if (!c.connect(cfg.addr, cfg.group)) {
            client = null
            active = null
        }
    }

    private fun handleMessage(m: Message) {
        when (m.type) {
            MsgType.SESSIONS -> sessions = m.sessions
            MsgType.RESET -> {
                exitCode = null
                terminal.reset()
            }
            MsgType.STATUS -> {
                if (m.name == null || m.name == attachedName) {
                    sessionStatus = m.status
                    exitCode = m.code
                    terminal.setSize(m.cols, m.rows)
                }
            }
            MsgType.ERROR -> notice = m.err
        }
    }

    /** 打开会话：进入终端页并请求回放。 */
    fun attach(name: String) {
        val c = client ?: return
        attachedName = name
        sessionStatus = null
        exitCode = null
        terminal.reset()
        stage = Stage.TERMINAL
        // 终端页的 WebView 在进入 TERMINAL 后才创建；页面未就绪时
        // 交给 onReady 回调发起接入，避免重复请求与空转。
        if (terminal.isReady) {
            attachGen = terminal.readyGen
            c.attach(name)
        } else {
            attachGen = -1
        }
    }

    /** 返回会话列表并取消接入。 */
    fun closeSession() {
        attachedName?.let { client?.detach(it) }
        attachedName = null
        attachGen = -1
        sessionStatus = null
        exitCode = null
        stage = Stage.SESSIONS
    }

    /** 从终端页断开服务器，回到服务器列表。 */
    fun disconnect() {
        attachedName?.let { client?.detach(it) }
        client?.close()
        client = null
        active = null
        connected = false
        attachedName = null
        sessionStatus = null
        exitCode = null
        sessions = emptyList()
        stage = Stage.SERVERS
    }

    fun dismissNotice() {
        notice = null
    }

    fun sendText(text: String) {
        client?.sendInput(text.toByteArray(Charsets.UTF_8))
    }

    /** 发送方向键；根据 agent 是否启用 DECCKM 选择 CSI 或 SS3 前缀。 */
    fun sendArrow(dir: Char) {
        sendText(com.agrmt.app.term.Keys.arrow(dir, appCursorKeys))
    }

    fun sendQuick(text: String) {
        sendText(text)
    }

    val activeConfig: ServerConfig? get() = active

    override fun onCleared() {
        client?.close()
        client = null
        terminal.unbind()
        super.onCleared()
    }
}
