package com.agrmt.app.term

/** 快捷按键对应的字节序列。光标键序列取决于 agent 是否启用了 DECCKM。 */
object Keys {
    const val ESC = "\u001b"
    const val ENTER = "\r"
    const val TAB = "\t"
    const val SHIFT_TAB = "\u001b[Z"
    const val SLASH = "/"

    /** dir 取值 u/d/l/r，分别对应上/下/左/右。 */
    fun arrow(dir: Char, applicationCursorKeys: Boolean): String {
        val suffix = when (dir) {
            'u' -> "A"
            'd' -> "B"
            'r' -> "C"
            'l' -> "D"
            else -> throw IllegalArgumentException("未知方向: $dir")
        }
        return if (applicationCursorKeys) "\u001bO$suffix" else "\u001b[$suffix"
    }
}
