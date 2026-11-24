package com.agrmt.app.net

import com.agrmt.app.model.Message
import com.agrmt.app.model.MsgType
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit

/** 与 agrmt-srv 的 app 端连接：文本帧为 JSON 控制消息，二进制帧为终端原始字节。 */
class AgrmtClient(private val listener: Listener) {

    interface Listener {
        fun onOpen()
        fun onMessage(m: Message)
        fun onOutput(bytes: ByteArray)
        fun onClosed(reason: String)
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Volatile
    private var ws: WebSocket? = null

    @Volatile
    private var closedByUs = false

    /** 建立连接；地址非法时立即回调 onClosed 并返回 false。 */
    fun connect(addr: String, group: String): Boolean {
        val url = try {
            buildAppUrl(addr, group)
        } catch (e: IllegalArgumentException) {
            listener.onClosed(e.message ?: "地址无效")
            return false
        }
        closedByUs = false
        val req = Request.Builder().url(url).build()
        ws = httpClient.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                listener.onOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val m = runCatching { json.decodeFromString<Message>(text) }.getOrNull() ?: return
                listener.onMessage(m)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                listener.onOutput(bytes.toByteArray())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!closedByUs) {
                    listener.onClosed(reason.ifEmpty { "连接已关闭" })
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!closedByUs) {
                    listener.onClosed(t.message ?: "连接失败")
                }
            }
        })
        return true
    }

    fun attach(name: String) = sendText(Message(type = MsgType.ATTACH, name = name))

    fun detach(name: String) = sendText(Message(type = MsgType.DETACH, name = name))

    fun sendText(m: Message) {
        ws?.send(json.encodeToString(Message.serializer(), m))
    }

    /** 发送终端输入原始字节。 */
    fun sendInput(bytes: ByteArray) {
        ws?.send(bytes.toByteString())
    }

    /** 主动断开，不再向 UI 回调关闭事件。 */
    fun close() {
        closedByUs = true
        ws?.close(1000, null)
        ws = null
    }

    private companion object {
        // 重连会反复创建 AgrmtClient，OkHttpClient 持有线程池与连接池，必须共享复用。
        val httpClient: OkHttpClient = OkHttpClient.Builder()
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }
}
