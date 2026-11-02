package com.scorchsoft.ssmarkdown.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import com.scorchsoft.ssmarkdown.editor.EditorAction
import com.scorchsoft.ssmarkdown.editor.ViewMode
import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import com.scorchsoft.ssmarkdown.files.provider.ProviderResult
import com.scorchsoft.ssmarkdown.files.saf.FolderRow
import com.scorchsoft.ssmarkdown.files.saf.TreeEntry
import com.scorchsoft.ssmarkdown.fixtures.EditorFixture
import com.scorchsoft.ssmarkdown.folder.FolderAction
import com.scorchsoft.ssmarkdown.folder.FolderSource
import com.scorchsoft.ssmarkdown.markdown.edit.TextSelection
import com.scorchsoft.ssmarkdown.text.EditOp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Rubric #1, "unsaved state is readable from any screen in under 0.5 s":
 * the first edit makes something TalkBack reads as "Unsaved" appear
 * within **one frame** (16 ms) on every screen that shows a document:
 * Code, Split and Preview, portrait and landscape, and the folder drawer,
 * modal on a phone and docked on a tablet.
 *
 * End to end through the real view model and store: the edit goes in
 * through the document's surface (as typing does), the store marks the
 * buffer dirty, the view model publishes it, and the screen is checked
 * after exactly one frame of the paused Compose clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class UnsavedIndicatorTest {
    @get:Rule val rule = createComposeRule()

    @get:Rule val temp = TemporaryFolder()

    private val folderRows = ArrayList<FolderRow>()

    private val folders =
        object : FolderSource {
            override fun list(
                treeUri: String,
                store: com.scorchsoft.ssmarkdown.files.document.DocumentStore?,
                showAllFiles: Boolean,
            ) = ProviderResult.Ok(folderRows.toList())

            override fun rootOf(treeUri: String) = treeUri

            override fun create(
                parent: String,
                name: String,
            ) = ProviderResult.NotFound

            override fun rename(
                uri: String,
                name: String,
            ) = ProviderResult.NotFound

            override fun duplicate(
                uri: String,
                parent: String,
                name: String,
            ) = ProviderResult.NotFound

            override fun delete(uri: String) = ProviderResult.NotFound
        }

    private val fixture by lazy { EditorFixture(temp.root, UnconfinedTestDispatcher(), folders = folders) }

    /** What TalkBack reads as unsaved: the dot's label or the status line's word. */
    private val unsaved: SemanticsMatcher =
        hasContentDescription(UNSAVED, substring = true, ignoreCase = true) or
            hasText(UNSAVED, substring = true, ignoreCase = true)

    private fun showEditor(mode: ViewMode = ViewMode.Code) {
        val uri = fixture.file("notes.md", "# Notes\n\nfirst line\n")
        folderRows +=
            FolderRow(TreeEntry(DocumentUri(uri), "notes", "notes.md", false, 0, 0, "text/markdown"), 0, false, false)
        fixture.vm.openDocument(uri)
        fixture.vm.onAction(EditorAction.SelectMode(mode))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Themed(dark = false) {
                val state by fixture.vm.uiState.collectAsState()
                EditorFor(state, fixture.vm::onAction)
            }
        }
        rule.mainClock.advanceTimeByFrame()
        assertTrue("clean before the first edit", unsavedNodes() == 0)
    }

    /** The first keystroke, through the surface typing uses; then exactly one frame. */
    private fun typeAndWaitOneFrame() {
        val doc = fixture.vm.active ?: error("nothing open")
        doc.surface.apply(listOf(EditOp(0, "", "x")), TextSelection.caret(1))
        rule.mainClock.advanceTimeByFrame()
    }

    private fun unsavedNodes(): Int = rule.onAllNodes(unsaved).fetchSemanticsNodes().size

    private fun assertUnsavedWithinOneFrame(where: String) {
        typeAndWaitOneFrame()
        assertTrue("$where: nothing reads \"$UNSAVED\" one frame after the first edit", unsavedNodes() > 0)
    }

    @Test
    fun `Code view, portrait`() {
        showEditor(ViewMode.Code)
        assertUnsavedWithinOneFrame("Code")
    }

    @Test
    fun `Split view, portrait`() {
        showEditor(ViewMode.Split)
        assertUnsavedWithinOneFrame("Split")
    }

    @Test
    fun `Preview, portrait`() {
        showEditor(ViewMode.Preview)
        assertUnsavedWithinOneFrame("Preview")
    }

    @Test
    @Config(qualifiers = "w915dp-h412dp-420dpi")
    fun `Code view, landscape`() {
        showEditor(ViewMode.Code)
        assertUnsavedWithinOneFrame("landscape Code")
    }

    @Test
    @Config(qualifiers = "w915dp-h412dp-420dpi")
    fun `Preview, landscape`() {
        showEditor(ViewMode.Preview)
        assertUnsavedWithinOneFrame("landscape Preview")
    }

    @Test
    fun `the phone's folder drawer, opened after the first edit, marks the file unsaved`() {
        showEditor()
        fixture.vm.folder.onFolderPicked(TREE)
        fixture.vm.onAction(EditorAction.Folder(FolderAction.CloseDrawer))
        typeAndWaitOneFrame()
        fixture.vm.onAction(EditorAction.Folder(FolderAction.OpenDrawer))
        rule.mainClock.advanceTimeByFrame()
        // The editor behind the modal drawer is hidden from TalkBack; the drawer itself must say it.
        assertTrue("drawer row is not marked unsaved", unsavedNodes() > 0)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-240dpi")
    fun `the tablet's docked folder panel marks the file unsaved within one frame`() {
        showEditor()
        fixture.vm.folder.onFolderPicked(TREE)
        rule.mainClock.advanceTimeByFrame()
        val before = unsavedNodes()
        typeAndWaitOneFrame()
        // The header's mark and the panel row's mark: both, not just one.
        assertTrue("only $before -> ${unsavedNodes()} unsaved marks", unsavedNodes() >= before + 2)
    }

    private companion object {
        const val UNSAVED = "Unsaved"
        const val TREE = "content://tree/notes"
    }
}
