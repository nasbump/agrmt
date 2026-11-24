package com.agrmt.app.term

/**
 * 从 agent 的原始 PTY 输出还原纯文本历史。
 *
 * xterm 的回滚只存在于普通屏幕缓冲区：全屏 TUI（如 codex）使用备用屏幕后
 * 没有任何可回滚的内容，滑动只能转成方向键。这里独立维护一个文本屏幕，
 * 把输出还原成可翻阅的行，保证任何 agent 都能查看历史。
 *
 * 规则：
 * - 去掉全部转义序列（颜色、OSC 标题、字符集切换等），只保留可见字符；
 * - `\r` 回行首、`\b` 退一格、`\t` 按 8 列对齐；
 * - 处理光标定位（CUP/CUU/CUD/CUF/CUB/CHA/VPA）与擦除（J/K），
 *   TUI 的局部刷新不会把整屏内容拼成一整行；
 * - TUI 整屏重绘通常从左上角重新绘制：达到一定输出量后把上一帧收进历史，
 *   再继续在同一块屏上覆盖，因此可以回看之前的多屏内容；
 * - 切换备用屏幕或清屏（ED）时把当前屏收进历史后清空；
 * - utf8 多字节字符被分块截断时留到下一次追加再解码。
 */
class HistoryBuffer(private val maxChars: Int = DEFAULT_MAX_CHARS) {

    // 当前屏幕：行号即光标所在行，行内按列覆盖写入。
    private val screen = ArrayList<StringBuilder>()

    // 已提交的历史行（滚动出屏或整屏重绘之前的内容）。
    private val log = ArrayList<String>()

    private var row = 0
    private var col = 0
    private var totalChars = 0

    // 上次收帧时的屏幕内容，内容完全相同的重绘只记一次。
    private var lastFrame = ""

    // 距上次收帧的写入字符数，避免流式输出把历史刷满整屏副本。
    private var writesSinceFrame = 0

    // 未完成的 UTF-8 序列（最多 3 字节）。
    private val carry = ByteArray(4)
    private var carryLen = 0

    // 0 普通文本，1 ESC 之后，2 CSI 参数中，3 字符串序列（OSC/DCS）中，
    // 4 字符串序列中遇到 ESC，5 ESC 后单字符修饰符（字符集切换）。
    private var escape = 0
    private val csi = StringBuilder()

    /** 追加一段原始输出字节，可跨块调用。 */
    @Synchronized
    fun append(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        decode(bytes)
        trim()
    }

    /** 清空全部历史（终端重置或重新接入会话时调用）。 */
    @Synchronized
    fun clear() {
        screen.clear()
        log.clear()
        row = 0
        col = 0
        totalChars = 0
        lastFrame = ""
        writesSinceFrame = 0
        carryLen = 0
        escape = 0
        csi.setLength(0)
    }

    /** 当前历史的文本行快照（历史行 + 当前屏幕），去掉末尾空行。 */
    @Synchronized
    fun snapshot(): List<String> {
        val out = ArrayList<String>(log.size + screen.size)
        out.addAll(log)
        val current = currentFrame()
        // 清屏后重绘出与上一帧完全相同的画面时只保留历史里那一份。
        if (current.isNotEmpty() && current.joinToString("\n") != lastFrame) out.addAll(current)
        while (out.isNotEmpty() && out.last().isEmpty()) out.removeAt(out.size - 1)
        return out
    }

    // 当前屏幕去掉行尾空格与末尾空行后的文本行。
    private fun currentFrame(): List<String> {
        var end = screen.size
        while (end > 0 && trimRight(screen[end - 1]).isEmpty()) end--
        val out = ArrayList<String>(end)
        for (i in 0 until end) out.add(trimRight(screen[i]))
        return out
    }

    private fun decode(bytes: ByteArray) {
        val buf = if (carryLen == 0) {
            bytes
        } else {
            ByteArray(carryLen + bytes.size).also {
                System.arraycopy(carry, 0, it, 0, carryLen)
                System.arraycopy(bytes, 0, it, carryLen, bytes.size)
            }
        }
        carryLen = 0
        var end = buf.size
        if (end > 0) {
            var i = end - 1
            var tail = 0
            while (i >= 0 && tail < 3 && (buf[i].toInt() and 0xC0) == 0x80) {
                i--
                tail++
            }
            if (i >= 0) {
                val need = utf8Len(buf[i].toInt() and 0xFF)
                val have = end - i
                if (need > 1 && have < need) {
                    var valid = true
                    for (j in 1 until have) {
                        if ((buf[i + j].toInt() and 0xC0) != 0x80) valid = false
                    }
                    if (valid) {
                        System.arraycopy(buf, i, carry, 0, have)
                        carryLen = have
                        end = i
                    }
                }
            }
        }
        if (end > 0) feed(String(buf, 0, end, Charsets.UTF_8))
    }

    private fun utf8Len(lead: Int): Int = when {
        lead < 0x80 -> 1
        lead and 0xE0 == 0xC0 -> 2
        lead and 0xF0 == 0xE0 -> 3
        lead and 0xF8 == 0xF0 -> 4
        else -> 1
    }

    private fun feed(text: String) {
        for (ch in text) {
            when (escape) {
                1 -> when {
                    ch == '[' -> {
                        escape = 2
                        csi.setLength(0)
                    }
                    ch == ']' || ch == 'P' || ch == 'X' || ch == '^' || ch == '_' -> escape = 3
                    ch in "(#%)*+-./" -> escape = 5
                    else -> escape = 0
                }
                2 -> {
                    if (ch in '@'..'~') {
                        handleCsi(ch)
                        escape = 0
                    } else if (ch in ' '..'?') {
                        csi.append(ch)
                    } else {
                        escape = 0
                    }
                }
                3 -> when (ch) {
                    '\u0007' -> escape = 0
                    '\u001b' -> escape = 4
                }
                4 -> escape = if (ch == '\\') 0 else 3
                5 -> escape = 0
                else -> putChar(ch)
            }
        }
    }

    private fun handleCsi(final: Char) {
        val raw = csi.toString()
        if (raw.startsWith("?")) {
            // 进入/退出备用屏幕：先把当前内容收进历史，再从空屏继续。
            if ((final == 'h' || final == 'l') &&
                (raw.contains("1049") || raw.contains("1047") || raw.contains("47"))
            ) {
                flushFrame(clear = true)
            }
            return
        }
        if (raw.isNotEmpty() && raw[0] !in '0'..'9' && raw[0] != ';') return

        val params: List<Int> =
            if (raw.isEmpty()) emptyList() else raw.split(';').map { it.toIntOrNull() ?: 0 }
        fun arg(i: Int): Int = if (i < params.size && params[i] > 0) params[i] else 1

        when (final) {
            'H', 'f' -> {
                val r = (if (params.isNotEmpty() && params[0] > 0) params[0] else 1) - 1
                val c = (if (params.size > 1 && params[1] > 0) params[1] else 1) - 1
                // 从左上角重新绘制视为新的一帧：先保存上一次的完整画面。
                if (r == 0 && c == 0 && writesSinceFrame >= FRAME_MIN_WRITES) flushFrame(clear = false)
                row = r
                col = c
            }
            'A' -> row = (row - arg(0)).coerceAtLeast(0)
            'B' -> row += arg(0)
            'C' -> col += arg(0)
            'D' -> col = (col - arg(0)).coerceAtLeast(0)
            'E' -> {
                row += arg(0)
                col = 0
            }
            'F' -> {
                row = (row - arg(0)).coerceAtLeast(0)
                col = 0
            }
            'G' -> col = (if (params.isNotEmpty() && params[0] > 0) params[0] else 1) - 1
            'd' -> row = (if (params.isNotEmpty() && params[0] > 0) params[0] else 1) - 1
            'J' -> when (params.firstOrNull() ?: 0) {
                2, 3 -> flushFrame(clear = true)
                1 -> eraseAbove()
                else -> eraseBelow()
            }
            'K' -> eraseLine(params.firstOrNull() ?: 0)
            'S' -> scrollUp(arg(0))
            else -> Unit
        }
    }

    private fun putChar(ch: Char) {
        when (ch) {
            '\n' -> {
                row++
                col = 0
                totalChars++
            }
            '\r' -> col = 0
            '\b' -> col = (col - 1).coerceAtLeast(0)
            '\t' -> col = (col / 8 + 1) * 8
            '\u001b' -> {
                escape = 1
                csi.setLength(0)
            }
            else -> if (ch.code >= 0x20 && ch.code != 0x7f && ch.code !in 0x80..0x9f) {
                writeAt(row, col, ch)
                col++
            }
        }
    }

    private fun writeAt(r: Int, c: Int, ch: Char) {
        if (c > MAX_COL) return
        ensureRow(r)
        val line = screen[r]
        if (c < line.length) {
            line.setCharAt(c, ch)
        } else {
            val pad = c - line.length
            repeat(pad) { line.append(' ') }
            line.append(ch)
            totalChars += pad + 1
        }
        writesSinceFrame++
    }

    private fun ensureRow(r: Int) {
        if (r < MAX_SCREEN_ROWS) {
            while (screen.size <= r) screen.add(StringBuilder())
            return
        }
        // 普通流式输出一直往下写而从不回到顶部：把多余的行移入历史，限制内存。
        val move = r - MAX_SCREEN_ROWS + 1
        scrollUp(move)
        row = (row - move).coerceAtLeast(0)
    }

    private fun eraseLine(mode: Int) {
        if (row >= screen.size) return
        val line = screen[row]
        when (mode) {
            1 -> {
                if (col >= line.length) return
                for (i in 0..col.coerceAtMost(line.length - 1)) line.setCharAt(i, ' ')
            }
            2 -> {
                for (i in line.indices) line.setCharAt(i, ' ')
            }
            else -> {
                if (col >= line.length) return
                totalChars -= line.length - col
                line.setLength(col)
            }
        }
    }

    private fun eraseBelow() {
        if (row >= screen.size) return
        var removed = 0
        for (i in row until screen.size) {
            val line = screen[i]
            val keep = if (i == row) col.coerceAtMost(line.length) else 0
            removed += line.length - keep
            line.setLength(keep)
        }
        totalChars -= removed
    }

    private fun eraseAbove() {
        if (row >= screen.size) return
        var removed = 0
        for (i in 0..row) {
            val line = screen[i]
            val keep = if (i == row) (col + 1).coerceAtMost(line.length) else 0
            removed += line.length - keep
            line.setLength(keep)
        }
        totalChars -= removed
    }

    private fun scrollUp(n: Int) {
        if (n <= 0 || screen.isEmpty()) return
        val count = n.coerceAtMost(screen.size)
        for (i in 0 until count) {
            val line = screen[i]
            totalChars -= line.length
            if (line.isNotEmpty()) {
                val text = trimRight(line)
                log.add(text)
                totalChars += text.length + 1
            }
        }
        screen.subList(0, count).clear()
    }

    private fun hasContent(): Boolean = screen.any { line -> line.any { it != ' ' } }

    /**
     * 把当前屏幕收进历史。clear=true 时随后清空屏幕（备用屏幕切换、清屏），
     * clear=false 时保留屏幕内容供 TUI 继续覆盖重绘。
     */
    private fun flushFrame(clear: Boolean) {
        if (hasContent()) {
            var end = screen.size
            while (end > 0 && trimRight(screen[end - 1]).isEmpty()) end--
            val frame = ArrayList<String>(end)
            for (i in 0 until end) frame.add(trimRight(screen[i]))
            val signature = frame.joinToString("\n")
            if (signature != lastFrame) {
                log.addAll(frame)
                log.add("")
                lastFrame = signature
                totalChars += frame.sumOf { it.length + 1 }
            }
        }
        writesSinceFrame = 0
        if (!clear) return
        for (line in screen) totalChars -= line.length
        screen.clear()
        row = 0
        col = 0
    }

    private fun trim() {
        while (totalChars > maxChars && log.isNotEmpty()) {
            totalChars -= log.removeAt(0).length + 1
        }
        while (totalChars > maxChars && screen.size > 1) {
            totalChars -= screen.removeAt(0).length
            if (row > 0) row--
        }
        if (totalChars > maxChars) {
            totalChars = 0
            screen.clear()
            row = 0
            col = 0
        }
    }

    private fun trimRight(line: CharSequence): String {
        var end = line.length
        while (end > 0 && line[end - 1] == ' ') end--
        return if (end == line.length) line.toString() else line.subSequence(0, end).toString()
    }

    private companion object {
        const val DEFAULT_MAX_CHARS = 300_000
        const val MAX_SCREEN_ROWS = 5000
        const val MAX_COL = 2000

        // 整屏重绘收帧的最小写入量：低于该值视为同一帧的局部刷新，不重复记录。
        const val FRAME_MIN_WRITES = 256
    }
}
