package com.example.sploot

import com.example.sploot.ble.ReconnectionBackoff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ReconnectionBackoffTest {

    @Test
    fun `first attempt waits 1 second`() {
        assertEquals(1_000L, ReconnectionBackoff.delayForAttempt(0))
    }

    @Test
    fun `doubles up to cap`() {
        assertEquals(2_000L, ReconnectionBackoff.delayForAttempt(1))
        assertEquals(4_000L, ReconnectionBackoff.delayForAttempt(2))
        assertEquals(8_000L, ReconnectionBackoff.delayForAttempt(3))
        assertEquals(16_000L, ReconnectionBackoff.delayForAttempt(4))
        assertEquals(32_000L, ReconnectionBackoff.delayForAttempt(5))
    }

    @Test
    fun `capped at 60 seconds`() {
        assertEquals(60_000L, ReconnectionBackoff.delayForAttempt(6))
        assertEquals(60_000L, ReconnectionBackoff.delayForAttempt(7))
        assertEquals(60_000L, ReconnectionBackoff.delayForAttempt(50))
    }

    @Test
    fun `negative attempt rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            ReconnectionBackoff.delayForAttempt(-1)
        }
    }
}
