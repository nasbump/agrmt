package com.agrmt.app.ui

import android.app.Application
import android.os.Handler
import android.os.Looper
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
import com.agrmt.app.net.buildAppUrl
import com.agrmt.app.term.TerminalController

/** 界面所处的阶段。 */
enum class Stage { SERVERS, SESSIONS, TERMINAL }

/** 重连退避：第 attempt 次（从 0 开始）等待的毫秒数，1s 起翻倍，封顶 10s。 */
internal fun reconnectDelayMs(attempt: Int): Long =
    minOf(MAX_RECONNECT_DELAY_MS, BASE_RECONNECT_DELAY_MS shl minOf(attempt, 4))

private const val BASE_RECONNECT_DELAY_MS = 1_000L
private const val MAX_RECONNECT_DELAY_MS = 10_000L

/** 主界面状态与 srv 连接的唯一管理者。 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val store = ConfigStore(app)
    private var active: ServerConfig? = null

    private val main = Handler(Looper.getMainLooper())

    // 重连采用指数退避（1s 起，封顶 10s），成功后清零。
    private var reconnectAttempt = 0
    private var reconnectPending = false
    private val reconnectRunnable = Runnable {
        reconnectPending = false
        if (active != null && client == null) startClient()
    }

    val terminal = TerminalController()

    @Volatile
    private var client: AgrmtClient? = null

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
        stopConnection()
        notice = null
        sessions = emptyList()
        attachedName = null
        attachGen = -1
        sessionStatus = null
        exitCode = null
        active = cfg
        startClient()
    }

    /** 建立一次连接；意外断开后由 onClosed 统一安排重连。 */
    private fun startClient() {
        val cfg = active ?: return
        // 地址非法时 AgrmtClient 会同步回调 onClosed，这里先校验，避免误触发重连。
        try {
            buildAppUrl(cfg.addr, cfg.group)
        } catch (e: IllegalArgumentException) {
            notice = e.message ?: "srv 地址无效"
            active = null
            stage = Stage.SERVERS
            return
        }

        lateinit var c: AgrmtClient
        // 是否成功建立过连接：只有连上之后再断开才自动重连，
        // 首次连接就失败说明地址或网络有问题，直接提示用户，避免无意义的重试。
        var opened = false
        c = AgrmtClient(object : AgrmtClient.Listener {
            override fun onOpen() {
                if (client !== c) return
                opened = true
                connected = true
                reconnectAttempt = 0
                notice = null
                if (stage == Stage.SERVERS) stage = Stage.SESSIONS
                // 重连后重新接入当前会话，srv 会重发 reset、状态与全量回放；
                // 页面未就绪时交给 onReady 发起，避免重复请求。
                attachedName?.let { name ->
                    terminal.reset()
                    if (terminal.isReady) {
                        attachGen = terminal.readyGen
                        c.attach(name)
                    } else {
                        attachGen = -1
                    }
                }
            }

            override fun onMessage(m: Message) {
                if (client !== c) return
                handleMessage(m)
            }

            override fun onOutput(bytes: ByteArray) {
                if (client !== c) return
                terminal.append(bytes)
            }

            override fun onClosed(reason: String) {
                if (client !== c) return
                client = null
                connected = false
                if (active == null) return
                if (!opened) {
                    notice = "连接失败: $reason"
                    active = null
                    attachedName = null
                    attachGen = -1
                    stage = Stage.SERVERS
                    return
                }
                notice = "连接已断开，正在重连: $reason"
                scheduleReconnect()
            }
        })
        client = c
        connected = false
        if (!c.connect(cfg.addr, cfg.group)) {
            // 连接没能建立（地址非法）：停止重连并退回服务器列表。
            stopConnection()
            stage = Stage.SERVERS
        }
    }

    /** 按指数退避安排下一次重连，连接成功后退避清零。 */
    private fun scheduleReconnect() {
        if (reconnectPending) return
        reconnectPending = true
        val delay = reconnectDelayMs(reconnectAttempt)
        reconnectAttempt++
        main.postDelayed(reconnectRunnable, delay)
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

    /** 从终端页断开服务器，回到服务器列表；同时取消自动重连。 */
    fun disconnect() {
        stopConnection()
        attachedName = null
        attachGen = -1
        sessionStatus = null
        exitCode = null
        sessions = emptyList()
        stage = Stage.SERVERS
    }

    /** 关闭连接并取消待执行的重连；不改变界面阶段。 */
    private fun stopConnection() {
        main.removeCallbacks(reconnectRunnable)
        reconnectPending = false
        reconnectAttempt = 0
        val c = client
        client = null
        if (c != null) {
            attachedName?.let { c.detach(it) }
            c.close()
        }
        connected = false
        active = null
    }

    /** 系统返回键：终端页退回会话列表，会话列表页断开连接回到服务器列表。 */
    fun onBack() {
        when (stage) {
            Stage.TERMINAL -> closeSession()
            Stage.SESSIONS -> disconnect()
            Stage.SERVERS -> Unit
        }
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
        main.removeCallbacks(reconnectRunnable)
        reconnectPending = false
        client?.close()
        client = null
        terminal.unbind()
        super.onCleared()
    }

    private companion object {
        const val BASE_RECONNECT_DELAY_MS = 1_000L
        const val MAX_RECONNECT_DELAY_MS = 10_000L
    }
}
