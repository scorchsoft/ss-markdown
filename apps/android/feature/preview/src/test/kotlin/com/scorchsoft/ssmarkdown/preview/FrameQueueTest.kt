package com.scorchsoft.ssmarkdown.preview

import com.scorchsoft.ssmarkdown.preview.bridge.BridgeCodec
import com.scorchsoft.ssmarkdown.preview.bridge.HostMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.Executor

/** PERF-12: a large load is framed off the main thread, and nothing overtakes it. */
@RunWith(RobolectricTestRunner::class)
class FrameQueueTest {
    /** An executor that runs nothing until told to. */
    private class Held : Executor {
        val tasks = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }

        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }

    private val direct = Executor { it.run() }

    @Test
    fun `a large load is cut on the encoder and nothing sent after it overtakes it`() {
        val encoder = Held()
        val queue = FrameQueue(encoder, direct, frameChars = 4)
        val posted = ArrayList<String>()
        queue.onReady = { queue.drainTo(posted::add) }
        queue.add(
            HostMessage.Settings(
                com.scorchsoft.ssmarkdown.preview.bridge
                    .PreviewSettings(),
            ),
        )
        queue.add(HostMessage.Load(1, StringBuilder("abcdefghij")))
        queue.add(HostMessage.Ack(2))
        assertEquals(1, posted.size)
        encoder.runAll()
        assertEquals(listOf("abcd", "efgh", "ij"), posted.subList(2, 5))
        assertEquals(3, JSONObject(posted[1]).getInt("chunks"))
        assertTrue(!JSONObject(posted[1]).has("text"))
        assertEquals("ack", JSONObject(posted[5]).getString("type"))
    }

    @Test
    fun `frames never split a surrogate pair and join back to the text`() {
        val text = "ab😀cd😀😀e".repeat(5)
        for (size in 2..7) {
            val frames = BridgeCodec.frames(HostMessage.Load(1, text), size)
            val parts = frames.drop(1)
            assertEquals(text, parts.joinToString(""))
            parts.forEach { assertTrue(it, !Character.isHighSurrogate(it.last())) }
        }
    }

    @Test
    fun `clear drops a load that is still being cut`() {
        val encoder = Held()
        val queue = FrameQueue(encoder, direct, frameChars = 2)
        val posted = ArrayList<String>()
        queue.onReady = { queue.drainTo(posted::add) }
        queue.add(HostMessage.Load(1, "abcdef"))
        queue.clear()
        encoder.runAll()
        assertEquals(emptyList<String>(), posted)
    }
}
