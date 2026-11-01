package com.scorchsoft.ssmarkdown.preview

import com.scorchsoft.ssmarkdown.preview.bridge.PageMessage
import java.util.concurrent.Executor

/**
 * Decodes the page's messages without parsing a large one on the main
 * thread (performance review PERF-23): an exported 2x Mermaid PNG arrives
 * as several MB of base64 inside JSON. Small messages decode inline; a
 * large one, and anything that arrives while one is in flight, decodes on
 * [background] and is delivered back through [main], so the order the
 * page sent them in is the order they are handled in.
 *
 * [onData] is called on the main thread, which also owns the count.
 */
internal class OrderedDecoder(
    private val background: Executor,
    private val main: Executor,
    private val decode: (String) -> PageMessage?,
    private val deliver: (PageMessage) -> Unit,
    private val inlineLimitChars: Int = INLINE_LIMIT_CHARS,
) {
    private var inFlight = 0

    /** Messages decoded off the calling thread so far; the tests read it. */
    var decodedInBackground: Int = 0
        private set

    /** One message from the page, as its raw text. */
    fun onData(data: String) {
        if (inFlight == 0 && data.length <= inlineLimitChars) {
            decode(data)?.let(deliver)
            return
        }
        inFlight++
        decodedInBackground++
        background.execute {
            val message = decode(data)
            main.execute {
                inFlight--
                message?.let(deliver)
            }
        }
    }

    private companion object {
        /** About a frame's worth of JSON parsing on a phone; edits and scroll reports are far smaller. */
        const val INLINE_LIMIT_CHARS = 64 * 1024
    }
}
