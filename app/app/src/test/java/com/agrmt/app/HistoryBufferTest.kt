package com.agrmt.app

import com.agrmt.app.term.HistoryBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** HistoryBuffer 的还原规则：去转义、回车覆盖、光标定位、备用屏幕收帧。 */
class HistoryBufferTest {

    private fun lines(vararg chunks: String): List<String> {
        val buf = HistoryBuffer()
        for (chunk in chunks) buf.append(chunk.toByteArray())
        return buf.snapshot()
    }

    @Test
    fun stripsColorAndOscSequences() {
        val out = lines("\u001b[32mgreen\u001b[0m plain\u001b]0;title\u0007!")
        assertEquals(listOf("green plain!"), out)
    }

    @Test
    fun carriageReturnOverwritesLine() {
        val out = lines("progress 10%\rprogress 100%\n")
        assertEquals(listOf("progress 100%"), out)
    }

    @Test
    fun backspaceDeletesPreviousChar() {
        val out = lines("ab\bc\n")
        assertEquals(listOf("ac"), out)
    }

    @Test
    fun keepsLinesAcrossChunks() {
        val out = lines("one\ntw", "o\nthree\n")
        assertEquals(listOf("one", "two", "three"), out)
    }

    @Test
    fun decodesUtf8SplitAcrossChunks() {
        val bytes = "中文".toByteArray()
        val buf = HistoryBuffer()
        buf.append(bytes.copyOfRange(0, 4))
        buf.append(bytes.copyOfRange(4, bytes.size))
        assertEquals(listOf("中文"), buf.snapshot())
    }

    @Test
    fun cursorPositioningRedrawsInPlace() {
        // TUI 常见写法：定位到第 2 行再改写，不能拼成一行。
        val out = lines("header\nold text\n\u001b[2;1Hnew text\n")
        assertEquals(listOf("header", "new text"), out)
    }

    @Test
    fun cursorUpThenNewlineKeepsScreenShape() {
        // 上移两行后回行首重写，下方的旧内容应保留。
        val out = lines("a\nb\nc\n\u001b[3A\u001b[1Gd\n")
        assertEquals(listOf("d", "b", "c"), out)
    }

    @Test
    fun eraseDisplayCommitsFrameAndClears() {
        val buf = HistoryBuffer()
        buf.append("first frame\n".toByteArray())
        buf.append("\u001b[2J\u001b[Hsecond\n".toByteArray())
        assertEquals(listOf("first frame", "", "second"), buf.snapshot())
    }

    @Test
    fun alternateScreenKeepsPreviousFrame() {
        val buf = HistoryBuffer()
        buf.append("shell output\n".toByteArray())
        buf.append("\u001b[?1049h".toByteArray())
        buf.append("tui full screen\n".toByteArray())
        assertEquals(listOf("shell output", "", "tui full screen"), buf.snapshot())
    }

    @Test
    fun duplicateFramesAreRecordedOnce() {
        val buf = HistoryBuffer()
        buf.append("frame\n".toByteArray())
        buf.append("\u001b[2J\u001b[Hframe\n".toByteArray())
        assertEquals(listOf("frame"), buf.snapshot())
    }

    @Test
    fun redrawnDifferentFrameIsKept() {
        val buf = HistoryBuffer()
        buf.append("step 1\n".toByteArray())
        buf.append("\u001b[2J\u001b[Hstep 2\n".toByteArray())
        assertEquals(listOf("step 1", "", "step 2"), buf.snapshot())
    }

    @Test
    fun trimsOldLinesBeyondBudget() {
        val buf = HistoryBuffer(maxChars = 40)
        repeat(200) { buf.append("line-$it-abcdefgh\n".toByteArray()) }
        val out = buf.snapshot()
        assertTrue(out.isNotEmpty())
        assertEquals("line-199-abcdefgh", out.last())
        assertTrue(out.none { it == "line-0-abcdefgh" })
    }

    @Test
    fun ignoresTrailingBlankLines() {
        val out = lines("done\n\n\n")
        assertEquals(listOf("done"), out)
    }

    @Test
    fun clearResetsHistory() {
        val buf = HistoryBuffer()
        buf.append("secret\n".toByteArray())
        buf.clear()
        assertTrue(buf.snapshot().isEmpty())
    }
}
