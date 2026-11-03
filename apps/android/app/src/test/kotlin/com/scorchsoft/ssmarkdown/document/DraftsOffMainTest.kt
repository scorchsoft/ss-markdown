package com.scorchsoft.ssmarkdown.document

import com.scorchsoft.ssmarkdown.editor.EditorBanner
import com.scorchsoft.ssmarkdown.editor.LineChange
import com.scorchsoft.ssmarkdown.editor.MessageKind
import com.scorchsoft.ssmarkdown.files.document.SetAsideEdits
import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import com.scorchsoft.ssmarkdown.fixtures.EditorFixture
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.coroutines.CoroutineContext

/**
 * Creating and deleting draft files is disk work, so it runs on the I/O
 * dispatcher, never the main thread (StrictMode, ANR): Save as of a draft
 * and the recovered-copy paths. The order is unchanged: a draft goes only
 * after a verified save (DraftWorkflowTest pins that a failed Save as keeps
 * it, through this same code).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DraftsOffMainTest {
    @get:Rule val temp = TemporaryFolder()

    /**
     * Runs each I/O block at once, and notes any change to the drafts
     * folder that happened between blocks, which is on the main thread.
     */
    private class DraftsWatch(
        private val folder: File,
    ) : CoroutineDispatcher() {
        val onMain = ArrayList<String>()

        /** The changes made inside I/O blocks. */
        val onIo = ArrayList<String>()
        private var last = listing()

        private fun listing(): Set<String> =
            folder
                .list()
                .orEmpty()
                .toSet()

        /** Records whatever changed since the last I/O block finished. */
        fun check() {
            val now = listing()
            if (now != last) onMain += "added ${now - last}, removed ${last - now}"
            last = now
        }

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            check()
            block.run()
            val now = listing()
            if (now != last) onIo += "added ${now - last}, removed ${last - now}"
            last = now
        }
    }

    /**
     * Main is a queued test dispatcher, so the code after a `withContext(io)`
     * block runs as its own main-thread step, not inline inside the block.
     */
    private val main = StandardTestDispatcher()

    private fun fixture(): Pair<EditorFixture, DraftsWatch> {
        val watch = DraftsWatch(File(temp.root, "drafts"))
        return EditorFixture(temp.root, main, io = watch) to watch
    }

    /** Runs the main thread until it has nothing left to do, then looks at the folder once more. */
    private fun DraftsWatch.settle() {
        main.scheduler.advanceUntilIdle()
        check()
    }

    private fun ActiveDocument.typeAtEnd(typed: String) {
        val at = text.text.length
        val line = text.text.positionAt(at).line
        text.replace(at, 0, typed)
        onTextEdited(LineChange(line, 0, typed.count { it == '\n' }), text.version)
    }

    private fun EditorFixture.uri(): DocumentUri = vm.active?.open?.uri ?: error("nothing open")

    @Test
    fun `Save as of a draft deletes the draft off the main thread, after the save`() {
        val (f, watch) = fixture()
        f.vm.start()
        watch.settle()
        val draft = f.uri()
        assertTrue(draft.isDraft)
        f.vm.active?.typeAtEnd("# Notes\n")
        f.vm.saves.onSaveLocation(f.file("notes.md", ""))
        watch.settle()
        assertEquals("# Notes\n", f.disk("notes.md"))
        assertNull("the draft is gone", f.drafts.resolve(draft)?.takeIf { it.exists() })
        assertEquals(emptyList<String>(), watch.onMain)
    }

    @Test
    fun `a recovered copy creates its draft off the main thread`() {
        val first = EditorFixture(temp.root, UnconfinedTestDispatcher())
        first.vm.openDocument(first.file("d.md", "base\n"))
        first.vm.active?.typeAtEnd("precious")
        first.vm.onPause()
        File(temp.root, "files/d.md").delete()
        val (f, watch) = fixture()

        f.vm.start()
        watch.settle()
        watch.settle()

        assertTrue(f.uri().isDraft)
        assertTrue(f.vm.uiState.value.banner is EditorBanner.RecoveredCopy)
        assertEquals(emptyList<String>(), watch.onMain)
    }

    @Test
    fun `a copy that cannot be made deletes its empty draft off the main thread`() {
        val (f, watch) = fixture()
        f.vm.start()
        watch.settle()
        val before = f.drafts.list().map { it.uri }
        val ioBefore = watch.onIo.size
        f.vm.recovery.openSetAsideAsCopy(SetAsideEdits(null, null, 0, 0, File(temp.root, "missing")))
        watch.settle()
        assertEquals(
            MessageKind.ActionFailed,
            f.vm.uiState.value.message
                ?.kind,
        )
        assertEquals("the empty draft is not left behind", before, f.drafts.list().map { it.uri })
        // Made and removed again: both on the I/O dispatcher, not netted out between two of its blocks.
        assertEquals(watch.onIo.toString(), 2, watch.onIo.size - ioBefore)
        assertEquals(emptyList<String>(), watch.onMain)
    }
}
