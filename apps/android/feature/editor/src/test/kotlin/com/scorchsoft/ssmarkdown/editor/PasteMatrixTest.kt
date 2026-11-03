package com.scorchsoft.ssmarkdown.editor

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import com.scorchsoft.ssmarkdown.text.PieceTree
import com.scorchsoft.ssmarkdown.text.TextDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.Executor

/**
 * Paste in Code, through the real entry points (the keyboard's editing
 * panel, the text toolbar's item and Ctrl+V) with real `ClipData`, over
 * every kind of block and every kind of clip a user copies
 * (docs/ux-spec.md, "Paste"). Code is raw text: what goes in is the clip's
 * plain text exactly, with only line endings, NUL and a leading BOM
 * changed, and no marker is ever added or taken away. The one exception is
 * a bare web address pasted over selected text outside code, which links it.
 */
@RunWith(RobolectricTestRunner::class)
class PasteMatrixTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val clipboard = context.getSystemService(ClipboardManager::class.java)
    private val frames = ArrayDeque<Runnable>()
    private val view =
        MarkdownCodeView(context).apply {
            paste.background = Executor { it.run() }
            paste.main = Executor { frames.addLast(it) }
            measure(
                View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, WIDTH, HEIGHT)
        }

    private val text: String get() = view.document.text.toString()

    /** A block, with `‸` where the caret goes. */
    private enum class Block(
        val marked: String,
    ) {
        PARAGRAPH("# Title\n\nSome ‸text here.\n\nAfter\n"),
        HEADING("## Head‸ing\n\nBody\n"),
        FENCE("Intro\n\n```kotlin\nval a = ‸1\n```\n\nAfter\n"),
        LIST("- one\n- tw‸o\n- three\n"),
        NUMBERED("1. one\n2. tw‸o\n3. three\n"),
        TASK("- [ ] fi‸rst\n- [x] done\n"),
        TABLE("| a | b |\n| - | - |\n| c‸1 | d |\n"),
        QUOTE("> quo‸ted\n> line\n"),
        EMPTY("‸"),
    }

    /** What the user copied: the plain text and/or HTML on the clip, and what Code must insert. */
    private enum class Clip(
        val plain: String?,
        val html: String?,
        val inserted: String,
    ) {
        ONE_LINE("plain *star* _under_ [x] `tick` # | -", null, "plain *star* _under_ [x] `tick` # | -"),
        MULTI_LINE("first\n- item\n> quote\n\n```\ncode\n```", null, "first\n- item\n> quote\n\n```\ncode\n```"),
        CRLF("one\r\ntwo\rthree\r\n", null, "one\ntwo\nthree\n"),
        HTML_ONLY(
            null,
            "<html><head><style>p{color:red}</style><script>alert(1)</script></head><body>" +
                "<p>Hi <b>there</b> <img src=\"https://example.com/x.png\"></p><p>Two</p></body></html>",
            "Hi there \n\nTwo",
        ),
        HTML_AND_PLAIN("Hi **there**", "<p>Hi <b>there</b></p>", "Hi **there**"),
        UNICODE(
            "👩\u200D👩\u200D👧 مرحبا بالعالم e\u0301 🇬🇧\u00A0x\u200By",
            null,
            "👩\u200D👩\u200D👧 مرحبا بالعالم e\u0301 🇬🇧\u00A0x\u200By",
        ),
        CONTROL("\uFEFFbefore\u0000after", null, "beforeafter"),
        BARE_URL("https://example.com/a_b?q=1", null, "https://example.com/a_b?q=1"),
    }

    /** The three ways a user pastes in Code; each must reach the same paste. */
    private enum class Entry { KEYBOARD_PANEL, TOOLBAR, CTRL_V }

    private fun show(marked: String): Pair<Int, Int> {
        val start = marked.indexOf('‸').coerceAtLeast(0)
        val plain = marked.replace("‸", "")
        view.setDocument(TextDocument(PieceTree.of(plain)))
        view.setCaret(start)
        return start to start
    }

    private fun put(
        plain: String?,
        html: String?,
    ) {
        val clip =
            when {
                html != null && plain != null -> {
                    ClipData.newHtmlText(null, plain, html)
                }

                html != null -> {
                    ClipData(
                        ClipDescription(null, arrayOf(ClipDescription.MIMETYPE_TEXT_HTML)),
                        ClipData.Item(null, html),
                    )
                }

                else -> {
                    ClipData.newPlainText(null, plain)
                }
            }
        clipboard.setPrimaryClip(clip)
    }

    private fun paste(entry: Entry) {
        when (entry) {
            Entry.KEYBOARD_PANEL -> {
                view.onCreateInputConnection(EditorInfo()).performContextMenuAction(android.R.id.paste)
            }

            Entry.TOOLBAR -> {
                view.runCommand(EditorCommand.PASTE)
            }

            Entry.CTRL_V -> {
                view.dispatchKeyEvent(
                    KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_V, 0, KeyEvent.META_CTRL_ON),
                )
                view.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_V, 0, KeyEvent.META_CTRL_ON))
            }
        }
        while (frames.isNotEmpty()) frames.removeFirst().run()
    }

    @Test
    fun `EB-CLIP-06 to 16 every clip goes into every block as copied, caret after it, one undo step`() {
        var cases = 0
        val failures = mutableListOf<String>()
        for (block in Block.entries) {
            for (clip in Clip.entries) {
                val entry = Entry.entries[cases % Entry.entries.size]
                val (at, _) = show(block.marked)
                val before = text
                put(clip.plain, clip.html)
                paste(entry)
                val label = "$clip into $block by $entry"
                val expected = before.substring(0, at) + clip.inserted + before.substring(at)
                if (text != expected) failures += "$label: got ${text.quoted()}, want ${expected.quoted()}"
                if (view.session.caret != at + clip.inserted.length) failures += "$label: caret ${view.session.caret}"
                view.undo()
                if (text != before) failures += "$label: undo left ${text.quoted()}"
                cases++
            }
        }
        assertEquals(failures.joinToString("\n"), 0, failures.size)
        assertEquals(Block.entries.size * Clip.entries.size, cases)
    }

    @Test
    fun `a paste replaces the selection`() {
        show("keep [replace me] keep")
        view.session.setSelection(6, 16)
        put("new", null)
        paste(Entry.TOOLBAR)
        assertEquals("keep [new] keep", text)
        assertEquals(9, view.session.caret)
    }

    @Test
    fun `EB-CLIP-01 copy then paste over the same selection is identity, and copy gives exactly the source`() {
        val source = "# T\n\n- **b** _i_ `c` [l](u)\n\n| a \\| b |\n| - |\n\n```\nx  \ty\n```\n👩\u200D👧 مرحبا"
        show(source)
        view.session.setSelection(2, source.length)
        view.runCommand(EditorCommand.COPY)
        assertEquals(
            source.substring(2),
            clipboard.primaryClip!!
                .getItemAt(0)
                .text
                .toString(),
        )
        paste(Entry.CTRL_V)
        assertEquals(source, text)
    }

    @Test
    fun `EB-CLIP-18 a large paste through the clipboard arrives whole and undoes in one step`() {
        show("head\n‸tail")
        val huge = "line of pasted text with *stars*\r\n".repeat(20_000)
        assertTrue(huge.length > ChunkedPaste.THRESHOLD_CHARS)
        put(huge, null)
        paste(Entry.KEYBOARD_PANEL)
        assertFalse(view.isPasting)
        val inserted = huge.replace("\r\n", "\n")
        assertEquals("head\n" + inserted + "tail", text)
        assertEquals(5 + inserted.length, view.session.caret)
        assertTrue(view.undo())
        assertEquals("head\ntail", text)
    }

    @Test
    fun `the view scrolls to the caret after a paste`() {
        show("‸end")
        put("line\n".repeat(400), null)
        paste(Entry.CTRL_V)
        val caretY = view.pointInView(view.session.caret).y
        assertTrue("caret at $caretY is on screen", caretY >= 0 && caretY < HEIGHT)
        assertTrue(view.scrollY > 0)
    }

    @Test
    fun `EB-LINK-05 and EB-UNDO-07 an address pasted over selected text links it, plain paste one Undo back`() {
        show("See the docs here.")
        view.session.setSelection(4, 12)
        put("https://example.com/a_(b)", null)
        paste(Entry.TOOLBAR)
        assertEquals("See [the docs](https://example.com/a_(b)) here.", text)
        assertEquals("See [the docs](https://example.com/a_(b))".length, view.session.caret)
        assertTrue(view.undo())
        assertEquals("first Undo leaves the plain paste (D9)", "See https://example.com/a_(b) here.", text)
        assertTrue(view.undo())
        assertEquals("See the docs here.", text)
    }

    @Test
    fun `a web address with brackets in the selection escapes them`() {
        show("a [x] b")
        view.session.setSelection(2, 5)
        put(" http://e.com/p \n", null)
        paste(Entry.CTRL_V)
        assertEquals("a [\\[x\\]](http://e.com/p) b", text)
    }

    @Test
    fun `EB-LINK-06 to 08 an address is pasted as text where a link would not belong`() {
        val url = "https://example.com/"
        val cases =
            listOf(
                // In a fence, in a code span, across lines, nothing selected, more than an address, not the web.
                Triple("```\nselect\n```", 4 to 10, url) to "```\n$url\n```",
                Triple("a `select` b", 3 to 9, url) to "a `$url` b",
                Triple("one\ntwo", 2 to 5, url) to "on${url}wo",
                Triple("ab", 1 to 1, url) to "a${url}b",
                Triple("ab", 0 to 2, "see $url") to "see $url",
                Triple("ab", 0 to 2, "mailto:x@y.z") to "mailto:x@y.z",
                Triple("ab", 0 to 2, "$url\n$url") to "$url\n$url",
                // EB-LINK-07: an address over an address replaces it.
                Triple("go https://old.com now", 3 to 18, url) to "go $url now",
            )
        for ((case, expected) in cases) {
            val (source, selection, clip) = case
            show(source)
            view.session.setSelection(selection.first, selection.second)
            put(clip, null)
            paste(Entry.TOOLBAR)
            assertEquals(source, expected, text)
        }
    }

    @Test
    fun `EB-CLIP-05 copy and cut with nothing selected do nothing, and an empty clip changes nothing`() {
        show("a‸b")
        clipboard.setPrimaryClip(ClipData.newPlainText(null, "kept"))
        assertFalse(view.runCommand(EditorCommand.COPY))
        assertFalse(view.runCommand(EditorCommand.CUT))
        assertEquals(
            "kept",
            clipboard.primaryClip!!
                .getItemAt(0)
                .text
                .toString(),
        )
        assertEquals("ab", text)
    }

    @Test
    fun `EB-CLIP-20 the keyboard's clipboard chip commits several lines as one undo step`() {
        show("- a‸")
        val connection = view.onCreateInputConnection(EditorInfo())
        connection.commitText("one\ntwo *x*", 1)
        assertEquals("- aone\ntwo *x*", text)
        assertTrue(view.undo())
        assertEquals("- a", text)
    }

    @Test
    fun `an empty clip changes nothing`() {
        show("a‸b")
        put("", null)
        paste(Entry.TOOLBAR)
        assertEquals("ab", text)
    }

    private fun String.quoted(): String = "\"" + replace("\n", "\\n").replace("\u0000", "\\0") + "\""

    private companion object {
        const val WIDTH = 1_080
        const val HEIGHT = 1_920
    }
}
