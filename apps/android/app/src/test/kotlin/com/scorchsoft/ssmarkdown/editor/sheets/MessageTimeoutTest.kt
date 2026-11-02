package com.scorchsoft.ssmarkdown.editor.sheets

import android.view.accessibility.AccessibilityManager
import org.junit.Assert.assertEquals
import org.junit.Test

/** UX-10: the message bar honours the user's accessibility timeout (WCAG 2.2.1). */
class MessageTimeoutTest {
    private val asked = ArrayList<Int>()

    /** A user who set "Time to take action" to 30 s. */
    private val slowUser: (Int, Int) -> Int = { _, flags ->
        asked += flags
        if (flags and AccessibilityManager.FLAG_CONTENT_CONTROLS != 0) 30_000 else 3_000
    }

    @Test
    fun `with no setting a message stays three seconds`() {
        assertEquals(3_000L, messageTimeoutMillis(undo = true, screenReader = false) { millis, _ -> millis })
        assertEquals(3_000L, messageTimeoutMillis(undo = false, screenReader = true) { millis, _ -> millis })
    }

    @Test
    fun `undo asks for the controls timeout and gets it`() {
        assertEquals(30_000L, messageTimeoutMillis(undo = true, screenReader = false, recommend = slowUser))
        assertEquals(
            AccessibilityManager.FLAG_CONTENT_TEXT or AccessibilityManager.FLAG_CONTENT_CONTROLS,
            asked.single(),
        )
    }

    @Test
    fun `a message without undo asks for the text timeout`() {
        assertEquals(3_000L, messageTimeoutMillis(undo = false, screenReader = false, recommend = slowUser))
        assertEquals(AccessibilityManager.FLAG_CONTENT_TEXT, asked.single())
    }

    @Test
    fun `with a screen reader undo stays long enough to reach`() {
        assertEquals(10_000L, messageTimeoutMillis(undo = true, screenReader = true) { millis, _ -> millis })
    }
}
