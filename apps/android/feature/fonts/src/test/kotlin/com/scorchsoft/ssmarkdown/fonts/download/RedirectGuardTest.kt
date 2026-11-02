package com.scorchsoft.ssmarkdown.fonts.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.net.URL

/** SEC-10: a redirect may only lead to HTTPS on Google's font hosts, and only a few times. */
class RedirectGuardTest {
    private val guard = RedirectGuard(setOf("fonts.googleapis.com", "fonts.gstatic.com"), maxRedirects = 2)
    private val from = URL("https://fonts.googleapis.com/css2?family=Lora")

    @Test
    fun `follows a relative or absolute redirect inside the allowed hosts`() {
        val relative = guard.next(from, "/css2?family=Lora&v=2", 1)
        assertEquals(URL("https://fonts.googleapis.com/css2?family=Lora&v=2"), relative)
        val absolute = guard.next(from, "https://fonts.gstatic.com/s/lora/x.ttf", 1)
        assertEquals(URL("https://fonts.gstatic.com/s/lora/x.ttf"), absolute)
    }

    @Test
    fun `refuses other hosts, plain HTTP, a missing location and too many hops`() {
        listOf(
            "https://evil.example/x.ttf",
            "http://fonts.gstatic.com/s/lora/x.ttf",
            "https://fonts.gstatic.com.evil.example/x",
            "https://user@evil.example/x",
            "//evil.example/x",
            null,
        ).forEach { location ->
            assertThrows(location.toString(), IOException::class.java) { guard.next(from, location, 1) }
        }
        assertThrows(IOException::class.java) { guard.next(from, "/css2", 3) }
    }
}
