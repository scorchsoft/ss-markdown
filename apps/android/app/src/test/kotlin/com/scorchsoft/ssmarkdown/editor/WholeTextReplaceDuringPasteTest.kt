package com.scorchsoft.ssmarkdown.editor

import android.app.Activity
import android.os.Looper
import com.scorchsoft.ssmarkdown.fixtures.EditorFixture
import com.scorchsoft.ssmarkdown.history.HistoryAction
import com.scorchsoft.ssmarkdown.history.HistoryViewModel
import com.scorchsoft.ssmarkdown.history.versions.DocumentKey
import com.scorchsoft.ssmarkdown.history.versions.RecordResult
import com.scorchsoft.ssmarkdown.history.versions.VersionKind
import com.scorchsoft.ssmarkdown.markdown.edit.TextSelection
import com.scorchsoft.ssmarkdown.text.EditOp
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
import java.io.File

/**
 * edge-cases "A whole-text replace during a large paste": Undo of Discard
 * and History's Restore replace the whole text with one op built on the
 * text as it stood. While a large paste was still going in by slices, the
 * paste landed inside that op, the op was refused as split, and the text
 * stayed as it was while the screen said it was restored.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class WholeTextReplaceDuringPasteTest {
    @get:Rule val temp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()

    /** Binds a Code view to [f]'s open document, as CodePaneHost does, and starts a large paste at [caret]. */
    private fun pasteInto(
        f: EditorFixture,
        caret: Int,
    ): MarkdownCodeView {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = MarkdownCodeView(activity)
        activity.setContentView(view)
        val doc = f.vm.active ?: error("nothing open")
        view.setDocument(doc.text)
        view.listener = doc
        f.vm.attach(view)
        view.setCaret(caret)
        view.pasteText("pasted line\n".repeat(50_000))
        val looper = shadowOf(Looper.getMainLooper())
        looper.runOneTask()
        assertTrue("the paste is still going in", view.isPasting)
        return view
    }

    @Test
    fun `Undo of a discard during a large paste brings the discarded text back`() {
        val first = EditorFixture(temp.root, dispatcher)
        first.vm.openDocument(first.file("k.md", "kept\n"))
        first.vm.active
            ?.surface
            ?.apply(listOf(EditOp(0, "", "typed ")), TextSelection.caret(6))
        first.vm.onPause()
        val second = EditorFixture(temp.root, dispatcher)
        second.vm.start()
        second.vm.onAction(EditorAction.RecoveryDiscard)
        assertEquals("kept\n", second.text())

        pasteInto(second, caret = 2)
        second.vm.onAction(EditorAction.MessageUndo)

        assertEquals("typed kept\n", second.text())
    }

    @Test
    fun `Restore from History during a large paste restores the version`() {
        val f = EditorFixture(temp.root, dispatcher)
        val uri = f.file("r.md", "now\n")
        f.vm.openDocument(uri)
        val recorded = f.versions.record(DocumentKey(uri), "old\n", VersionKind.Checkpoint)
        val id = (recorded as RecordResult.Recorded).version.id
        val history = HistoryViewModel(f.versions, f.vm, dispatcher, File(temp.root, "exports"))

        pasteInto(f, caret = 2)
        history.onAction(HistoryAction.Restore(id))

        assertEquals("old\n", f.text())
    }

    private fun EditorFixture.text() =
        vm.active
            ?.text
            ?.text
            .toString()
}
