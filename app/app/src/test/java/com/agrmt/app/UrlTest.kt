package com.agrmt.app

import com.agrmt.app.net.buildAppUrl
import com.agrmt.app.term.Keys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UrlTest {

    @Test
    fun addsHttpSchemeWhenMissing() {
        assertEquals("http://10.0.0.2:8989/ws/app?group=g1", buildAppUrl("10.0.0.2:8989", "g1"))
    }

    @Test
    fun convertsWsToHttp() {
        assertEquals("http://host:1/ws/app?group=g", buildAppUrl("ws://host:1", "g"))
    }

    @Test
    fun keepsHttpsForWss() {
        assertEquals("https://host/ws/app?group=g", buildAppUrl("wss://host", "g"))
    }

    @Test
    fun trimsTrailingSlash() {
        assertEquals("http://host:80/ws/app?group=g", buildAppUrl("http://host:80/", "g"))
    }

    @Test
    fun encodesGroup() {
        assertEquals("http://h/ws/app?group=a+b", buildAppUrl("h", "a b"))
    }

    @Test
    fun rejectsUnknownScheme() {
        assertThrows(IllegalArgumentException::class.java) { buildAppUrl("ftp://h", "g") }
    }

    @Test
    fun rejectsBlankInput() {
        assertThrows(IllegalArgumentException::class.java) { buildAppUrl("  ", "g") }
        assertThrows(IllegalArgumentException::class.java) { buildAppUrl("h", " ") }
    }
}

class KeysTest {

    @Test
    fun normalArrowUsesCsi() {
        assertEquals("\u001b[A", Keys.arrow('u', false))
        assertEquals("\u001b[D", Keys.arrow('l', false))
    }

    @Test
    fun applicationCursorArrowUsesSs3() {
        assertEquals("\u001bOB", Keys.arrow('d', true))
        assertEquals("\u001bOC", Keys.arrow('r', true))
    }

    @Test
    fun fixedKeys() {
        assertEquals("\r", Keys.ENTER)
        assertEquals("\t", Keys.TAB)
        assertEquals("\u001b[Z", Keys.SHIFT_TAB)
        assertEquals("/", Keys.SLASH)
        assertEquals("\u007f", Keys.BACKSPACE)
    }
}
