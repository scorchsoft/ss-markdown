package com.scorchsoft.ssmarkdown.preview

import com.scorchsoft.ssmarkdown.preview.bridge.BridgeCodec
import com.scorchsoft.ssmarkdown.preview.bridge.HostMessage
import java.util.concurrent.Executor

/**
 * Messages on their way to the page, kept in the order they were sent.
 *
 * Almost every message is a few hundred bytes and is encoded at once. A
 * large [HostMessage.Load] is cut into frames on [encoder], off the main
 * thread, because turning a 20 MB document into strings takes hundreds of
 * milliseconds (PERF-12); messages sent after it wait behind it, so the page
 * never sees an edit before the load it applies to. Frames come back
 * through [main], the thread that owns the port.
 *
 * Not thread-safe: call it from [main]'s thread only.
 */
internal class FrameQueue(
    private val encoder: Executor,
    private val main: Executor,
    private val frameChars: Int = BridgeCodec.FRAME_CHARS,
) {
    private class Slot(
        var frames: List<String>?,
    )

    private val slots = ArrayDeque<Slot>()

    /** Bumped by [clear], so frames encoded for a dropped page are thrown away. */
    private var generation = 0

    /** Called whenever frames at the head became ready. */
    var onReady: () -> Unit = {}

    /** Queues [message]; its frames are ready now, or after [encoder] has cut them. */
    fun add(message: HostMessage) {
        val large = message is HostMessage.Load && message.text.length > frameChars
        if (!large) {
            slots.addLast(Slot(BridgeCodec.frames(message, frameChars)))
            onReady()
            return
        }
        val slot = Slot(null)
        slots.addLast(slot)
        val queued = generation
        encoder.execute {
            val frames = BridgeCodec.frames(message, frameChars)
            main.execute {
                if (queued == generation) {
                    slot.frames = frames
                    onReady()
                }
            }
        }
    }

    /** Hands every frame that is ready, in order, to [post], stopping at the first message still encoding. */
    fun drainTo(post: (String) -> Unit) {
        while (true) {
            val frames = slots.firstOrNull()?.frames ?: return
            slots.removeFirst()
            frames.forEach(post)
        }
    }

    /** Forgets everything queued, including loads still encoding. */
    fun clear() {
        slots.clear()
        generation++
    }
}
