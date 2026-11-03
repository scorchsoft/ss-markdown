package com.scorchsoft.ssmarkdown.editor.preview

import android.app.Activity
import android.os.Looper
import com.scorchsoft.ssmarkdown.editor.EditorAction
import com.scorchsoft.ssmarkdown.editor.MarkdownCodeView
import com.scorchsoft.ssmarkdown.editor.ViewMode
import com.scorchsoft.ssmarkdown.editor.isPasting
import com.scorchsoft.ssmarkdown.editor.pasteText
import com.scorchsoft.ssmarkdown.fixtures.EditorFixture
import com.scorchsoft.ssmarkdown.preview.bridge.HostMessage
import com.scorchsoft.ssmarkdown.preview.bridge.PageMessage
import com.scorchsoft.ssmarkdown.preview.bridge.TextEdit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Split, a large paste going into the Code pane by slices, and an edit from
 * the Preview page (a task tick, a cell) arriving before it ends. The paste's
 * remaining slices go in at once when the page's edit is applied; the page
 * must hear about them as host edits, or it shows a document without them
 * and every later edit is placed by the wrong text (edge-cases "a Preview
 * edit during a large paste in Split").
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PreviewDuringPasteTest {
    @get:Rule val temp = TemporaryFolder()

    /** The page's copy of the text: the load, every host edit, and its own edit rebased as the page does. */
    private class PageModel {
        var text = StringBuilder()
        private val outstanding = ArrayList<TextEdit>()

        fun hear(message: HostMessage) {
            when (message) {
                is HostMessage.Load -> {
                    text = StringBuilder(message.text)
                    outstanding.clear()
                }

                is HostMessage.Edit -> {
                    // Every edit here inserts at or after the page's own one, which is at 0: it moves past it.
                    val shift = outstanding.sumOf { it.insert.length - (it.to - it.from) }
                    for (op in message.ops.orEmpty().sortedByDescending { it.from }) {
                        text.replace(op.from + shift, op.to + shift, op.insert)
                    }
                }

                is HostMessage.Ack -> {
                    outstanding.removeAt(0)
                }

                else -> {
                    Unit
                }
            }
        }

        fun type(edit: TextEdit) {
            text.replace(edit.from, edit.to, edit.insert)
            outstanding += edit
        }
    }

    @Test
    fun `a page edit during a large paste in Split leaves the page holding the host's text`() {
        val f = EditorFixture(temp.root, UnconfinedTestDispatcher())
        f.vm.openDocument(f.file("a.md", "- [ ] task\ntail\n"))
        val page = PageModel()
        f.vm.preview.attachPage { page.hear(it) }
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = MarkdownCodeView(activity)
        activity.setContentView(view)
        val doc = f.vm.active ?: error("nothing open")
        view.setDocument(doc.text)
        // As CodePaneHost wires it: the view reports its edits to the document.
        view.listener = doc
        f.vm.attach(view)
        f.vm.onAction(EditorAction.SelectMode(ViewMode.Split))

        view.setCaret("- [ ] task\n".length)
        view.pasteText("pasted line\n".repeat(50_000))
        val looper = shadowOf(Looper.getMainLooper())
        while (!view.isPasting) looper.runOneTask()
        looper.runOneTask()
        assertTrue("the paste is still going in", view.isPasting)

        // The page ticks the task, on the text it has seen.
        val tick = TextEdit(3, 4, "x")
        page.type(tick)
        f.vm.preview.onSourceEdits(f.vm.preview.rev, listOf(tick), PageMessage.UNSTAMPED)
        looper.idle()

        val host =
            f.vm.active
                ?.text
                ?.text
                .toString()
        assertEquals("- [x] task\n" + "pasted line\n".repeat(50_000) + "tail\n", host)
        assertEquals("the page shows the host's text", host, page.text.toString())
    }
}
