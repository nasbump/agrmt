package com.agrmt.app.term

import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import java.io.ByteArrayOutputStream

/**
 * 管理承载 xterm.js 的 WebView：把原始输出字节批量写入终端，并接收
 * 终端产生的按键与光标键模式。
 *
 * 所有 WebView 操作都切到主线程；页面就绪前先缓存字节，避免丢失回放内容。
 */
class TerminalController {

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var webView: WebView? = null

    @Volatile
    private var ready = false

    private val pending = ByteArrayOutputStream()
    private var flushPosted = false

    // 记录最近一次会话尺寸：status 可能早于页面加载完成到达，
    // 页面就绪后需要重新套用，避免终端停留在默认 80x24。
    @Volatile
    private var lastCols = 0

    @Volatile
    private var lastRows = 0

    /** 用户在终端里直接输入（含输入法、粘贴）的字节。 */
    var onInput: ((ByteArray) -> Unit)? = null

    /** 终端报告的光标键模式（DECCKM）变化。 */
    var onCursorKeys: ((Boolean) -> Unit)? = null

    /** 页面每次就绪时回调（含 WebView 重建），用于重新接入会话拿回放。 */
    var onReady: (() -> Unit)? = null

    val isReady: Boolean get() = ready

    /** 页面就绪次数；外部据此判断页面是否已重新加载。 */
    @Volatile
    var readyGen = 0
        private set

    /** 注入给页面的 JS 桥。 */
    inner class Bridge {
        @JavascriptInterface
        fun ready() {
            main.post { onReadyCallback() }
        }

        @JavascriptInterface
        fun input(b64: String) {
            val bytes = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull() ?: return
            main.post { onInput?.invoke(bytes) }
        }

        @JavascriptInterface
        fun cursorKeys(enabled: Boolean) {
            main.post { onCursorKeys?.invoke(enabled) }
        }
    }

    fun bind(wv: WebView) {
        webView = wv
    }

    fun unbind() {
        webView = null
        ready = false
        synchronized(pending) { pending.reset() }
    }

    private fun onReadyCallback() {
        ready = true
        readyGen++
        if (lastCols > 0 && lastRows > 0) {
            applySize(lastCols, lastRows)
        }
        scheduleFlush()
        onReady?.invoke()
    }

    /** 清屏并恢复到初始状态；待写入字节一并丢弃，由调用方随后补发回放。 */
    fun reset() {
        synchronized(pending) { pending.reset() }
        eval("agrmtReset()")
    }

    /** 用会话的行列数设置终端尺寸（页面会缩放字号以适配容器）。 */
    fun setSize(cols: Int, rows: Int) {
        if (cols <= 0 || rows <= 0) return
        lastCols = cols
        lastRows = rows
        applySize(cols, rows)
    }

    // 页面尚未就绪时不能直接注入 JS，先记下尺寸，等 ready 后补发。
    private fun applySize(cols: Int, rows: Int) {
        if (!ready) return
        eval("agrmtSetSize($cols, $rows)")
    }

    /** 追加 agent 输出的原始字节。 */
    fun append(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        synchronized(pending) { pending.write(bytes) }
        scheduleFlush()
    }

    private fun scheduleFlush() {
        if (!ready || flushPosted) return
        flushPosted = true
        main.postDelayed({ flushPosted = false; flush() }, 16)
    }

    private fun flush() {
        if (!ready) return
        repeat(8) {
            val chunk = synchronized(pending) {
                if (pending.size() == 0) return
                val all = pending.toByteArray()
                pending.reset()
                all
            }
            var off = 0
            while (off < chunk.size) {
                val end = minOf(off + CHUNK, chunk.size)
                val b64 = Base64.encodeToString(chunk, off, end - off, Base64.NO_WRAP)
                eval("agrmtWriteB64(\"$b64\")")
                off = end
            }
        }
        val more = synchronized(pending) { pending.size() > 0 }
        if (more) scheduleFlush()
    }

    private fun eval(js: String) {
        main.post { webView?.evaluateJavascript(js, null) }
    }

    private companion object {
        const val CHUNK = 64 * 1024
    }
}
