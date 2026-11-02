package com.scorchsoft.ssmarkdown.preview

import com.scorchsoft.ssmarkdown.preview.bridge.PageMessage
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.Executor

/** A large page message is never parsed on the main thread, and order survives (performance review PERF-23). */
class OrderedDecoderTest {
    private val background = ArrayDeque<Runnable>()
    private val main = ArrayDeque<Runnable>()
    private val delivered = ArrayList<String>()
    private val decodedOn = ArrayList<String>()
    private var thread = "main"

    private val decoder =
        OrderedDecoder(
            background = Executor { background.addLast(it) },
            main = Executor { main.addLast(it) },
            decode = { raw ->
                decodedOn += thread
                PageMessage.Error(raw.take(LABEL))
            },
            deliver = { delivered += (it as PageMessage.Error).message },
            inlineLimitChars = LIMIT,
        )

    private fun pump() {
        while (background.isNotEmpty() || main.isNotEmpty()) {
            thread = "background"
            while (background.isNotEmpty()) background.removeFirst().run()
            thread = "main"
            while (main.isNotEmpty()) main.removeFirst().run()
        }
    }

    @Test
    fun `small messages decode inline`() {
        decoder.onData("a")
        assertEquals(listOf("a"), delivered)
        assertEquals(0, decoder.decodedInBackground)
    }

    @Test
    fun `a large export decodes in the background and later small messages keep their place`() {
        decoder.onData("B" + "x".repeat(LIMIT))
        decoder.onData("c")
        assertEquals(emptyList<String>(), delivered)
        pump()
        assertEquals(listOf("B", "c"), delivered)
        assertEquals(listOf("background", "background"), decodedOn)
        decoder.onData("d")
        assertEquals(listOf("B", "c", "d"), delivered)
        assertEquals("main", decodedOn.last())
    }

    private companion object {
        const val LIMIT = 100
        const val LABEL = 1
    }
}
