package com.claudeauto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingLinkTest {
    private val fp = "7E3E8DC64305350301390DEC011A58C71725437282FD1601595D5FB73FFFB562"

    @Test
    fun `reads what the bridge puts in its QR code`() {
        val link = PairingLink.parse("claudeauto://pair?url=https%3A%2F%2F100.101.102.103%3A8787&code=XL23-WC8W&fp=$fp")!!
        assertEquals("https://100.101.102.103:8787", link.url)
        assertEquals("XL23-WC8W", link.code)
        assertEquals("7E:3E:8D:C6", link.fingerprint!!.take(11))
        assertEquals(95, link.fingerprint!!.length)
        assertFalse(link.isOnThisPhone)
    }

    @Test
    fun `a bridge on this phone is recognised`() {
        val link = PairingLink.parse("claudeauto://pair?url=https%3A%2F%2F127.0.0.1%3A8787&code=AAAA-BBBB&fp=${fp.lowercase()}")!!
        assertTrue(link.isOnThisPhone)
        assertEquals(fp.take(2), link.fingerprint!!.take(2))
    }

    @Test
    fun `a proxy with a public certificate has no fingerprint`() {
        val link = PairingLink.parse("claudeauto://pair?url=https%3A%2F%2Fpc.tail1234.ts.net&code=AAAA-BBBB")!!
        assertNull(link.fingerprint)
    }

    @Test
    fun `anything else is refused`() {
        assertNull(PairingLink.parse("https://example.com/pair?url=https://x&code=A"))
        assertNull(PairingLink.parse("claudeauto://pair?url=http%3A%2F%2F10.0.0.2%3A8787&code=AAAA-BBBB"))
        assertNull(PairingLink.parse("claudeauto://pair?url=https%3A%2F%2F10.0.0.2%3A8787"))
        assertNull(PairingLink.parse("claudeauto://pair?url=https%3A%2F%2F10.0.0.2%3A8787&code=A&fp=1234"))
        assertNull(PairingLink.parse("not a link at all"))
    }
}
