package com.agrmt.app

import com.agrmt.app.term.Keys
import com.agrmt.app.ui.reconnectDelayMs
import org.junit.Assert.assertEquals
import org.junit.Test

class ReconnectTest {

    @Test
    fun delayStartsAtOneSecondAndDoubles() {
        assertEquals(1_000L, reconnectDelayMs(0))
        assertEquals(2_000L, reconnectDelayMs(1))
        assertEquals(4_000L, reconnectDelayMs(2))
        assertEquals(8_000L, reconnectDelayMs(3))
    }

    @Test
    fun delayIsCappedAtTenSeconds() {
        assertEquals(10_000L, reconnectDelayMs(4))
        assertEquals(10_000L, reconnectDelayMs(30))
    }
}

class EscKeyTest {

    @Test
    fun escIsSingleEscapeByte() {
        assertEquals("\u001b", Keys.ESC)
    }
}
