package com.scorchsoft.ssmarkdown.power

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import com.scorchsoft.ssmarkdown.editor.EditorAction
import com.scorchsoft.ssmarkdown.editor.EditorEvent
import com.scorchsoft.ssmarkdown.editor.EditorOverlay
import com.scorchsoft.ssmarkdown.editor.EditorUiState
import com.scorchsoft.ssmarkdown.files.document.DocumentStore
import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import com.scorchsoft.ssmarkdown.files.provider.ProviderResult
import com.scorchsoft.ssmarkdown.files.saf.FolderRow
import com.scorchsoft.ssmarkdown.files.saf.TreeEntry
import com.scorchsoft.ssmarkdown.fixtures.EditorFixture
import com.scorchsoft.ssmarkdown.fixtures.LiveEditor
import com.scorchsoft.ssmarkdown.fixtures.Samples
import com.scorchsoft.ssmarkdown.fixtures.TapCounter
import com.scorchsoft.ssmarkdown.folder.FolderAction
import com.scorchsoft.ssmarkdown.folder.FolderEntryRow
import com.scorchsoft.ssmarkdown.folder.FolderReducer
import com.scorchsoft.ssmarkdown.folder.FolderSource
import com.scorchsoft.ssmarkdown.folder.FolderUiState
import com.scorchsoft.ssmarkdown.markdown.edit.TextSelection
import com.scorchsoft.ssmarkdown.preview.PrintOutcome
import com.scorchsoft.ssmarkdown.preview.PrintTarget
import com.scorchsoft.ssmarkdown.settings.RecentFile
import com.scorchsoft.ssmarkdown.share.DirectShareIds
import com.scorchsoft.ssmarkdown.share.DirectShareTargets
import com.scorchsoft.ssmarkdown.share.ShareAction
import com.scorchsoft.ssmarkdown.share.SharePhase
import com.scorchsoft.ssmarkdown.share.ShareSheet
import com.scorchsoft.ssmarkdown.share.ShareViewModel
import com.scorchsoft.ssmarkdown.share.ShortcutPublisher
import com.scorchsoft.ssmarkdown.share.ShortcutSpec
import com.scorchsoft.ssmarkdown.text.EditOp
import com.scorchsoft.ssmarkdown.theme.BuiltInThemes
import com.scorchsoft.ssmarkdown.themes.ThemeControls
import com.scorchsoft.ssmarkdown.themes.ThemeEditorScreen
import com.scorchsoft.ssmarkdown.themes.ThemeEditorUiState
import com.scorchsoft.ssmarkdown.ui.EditorFor
import com.scorchsoft.ssmarkdown.ui.Themed
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Rubric #9, power depth, as a tap-count audit that drives the real screens:
 *
 * - folder mode, Save all, no-wrap, custom themes, fonts, PDF and Mermaid
 *   export are each within two taps of ⋮ (Save all within two of ≡, its
 *   own button, as J7 counts it);
 * - any heading or recent file is three interactions through the
 *   Navigator: tap the title, type 2–3 letters, tap the result;
 * - Save all is also one tap after ⋮ while a folder file is unsaved;
 * - a snippet shared in lands in any recent file from the sheet over the
 *   sending app: the chooser, the file (unless it was the last used), Append;
 *   or, through a Direct Share target, the file in the chooser, then Append.
 *
 * Each test counts real clicks on the rendered UI and asserts where they
 * landed (the view model's state, the event the app acts on, the file).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class TapCountAuditTest {
    @get:Rule val rule = createComposeRule()

    @get:Rule val temp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()

    private fun editor(
        text: String = "# Notes\n\nHello\n",
        name: String = "notes.md",
    ): Pair<EditorFixture, TapCounter> {
        val f = EditorFixture(temp.root, dispatcher)
        f.vm.openDocument(f.file(name, text))
        rule.setContent { Themed(dark = false) { LiveEditor(f) } }
        return f to TapCounter(rule)
    }

    /** Taps after ⋮: the rubric's "within two taps of ⋮". */
    private fun TapCounter.afterMenu() = taps - 1

    @Test
    fun `folder mode is one tap after the menu`() {
        val (f, taps) = editor()
        taps.tap(MORE)
        taps.tap("Open folder…")
        assertTrue(EditorEvent.PickFolder in f.events)
        assertEquals(1, taps.afterMenu())
    }

    @Test
    fun `Save all is two taps, the folder button then Save all`() {
        val actions = ArrayList<EditorAction>()
        var state: EditorUiState by mutableStateOf(
            Samples.unsaved.copy(
                folder =
                    FolderUiState(
                        "product-docs",
                        "t",
                        listOf(FolderEntryRow("a", "a.md", 0, false, dirty = true)),
                        1,
                        unsavedCount = 1,
                    ),
            ),
        )
        rule.setContent {
            Themed(dark = false) {
                EditorFor(state) { action ->
                    actions += action
                    val folder = state.folder
                    if (action is EditorAction.Folder && folder != null) {
                        state = state.copy(folder = FolderReducer.reduce(folder, action.action))
                    }
                }
            }
        }
        val taps = TapCounter(rule)
        taps.tap("Open folder")
        taps.tap("Save all")
        assertTrue(EditorAction.Folder(FolderAction.SaveAll) in actions)
        assertEquals(2, taps.taps)
    }

    /** A folder of one file, notes.md, open and edited but not saved. */
    private fun unsavedFolderFile(): EditorFixture {
        val rows = ArrayList<FolderRow>()
        val f = EditorFixture(temp.root, dispatcher, folders = OneListFolder(rows))
        val uri = f.file("notes.md", "# Notes\n")
        rows +=
            FolderRow(TreeEntry(DocumentUri(uri), "notes", "notes.md", false, 0, 0, "text/markdown"), 0, false, false)
        f.vm.openDocument(uri)
        f.vm.folder.onFolderPicked(TREE)
        f.vm.onAction(EditorAction.Folder(FolderAction.CloseDrawer))
        val doc = f.vm.active ?: error("nothing open")
        doc.surface.apply(listOf(EditOp(0, "", "x")), TextSelection.caret(1))
        return f
    }

    @Test
    fun `Save all is one tap after the menu while a folder file is unsaved`() {
        val f = unsavedFolderFile()
        rule.setContent { Themed(dark = false) { LiveEditor(f) } }
        assertEquals(
            1,
            f.vm.uiState.value.folder
                ?.unsavedCount,
        )
        val taps = TapCounter(rule)
        taps.tap(MORE)
        taps.tap("Save all (1)")
        rule.waitUntil(TIMEOUT) { f.disk("notes.md") == "x# Notes\n" }
        assertEquals(
            0,
            f.vm.uiState.value.folder
                ?.unsavedCount,
        )
        assertEquals(1, taps.afterMenu())
    }

    @Test
    fun `the menu offers Save all only while a folder file is unsaved`() {
        val (_, taps) = editor()
        taps.tap(MORE)
        rule.onNodeWithText("Save as…").assertIsDisplayed()
        assertEquals(0, rule.onAllNodes(hasText("Save all", substring = true)).fetchSemanticsNodes().size)
    }

    @Test
    fun `no-wrap is two taps after the menu`() {
        val (f, taps) = editor()
        assertTrue(f.vm.uiState.value.codeWrap)
        taps.tap(MORE)
        taps.tap("View")
        taps.tap("No wrap")
        assertFalse(f.vm.uiState.value.codeWrap)
        assertEquals(2, taps.afterMenu())
    }

    @Test
    fun `custom themes and their fonts are two taps after the menu`() {
        val f = EditorFixture(temp.root, dispatcher)
        f.vm.openDocument(f.file("notes.md", "# Notes\n"))
        var editing: String? by mutableStateOf(null)
        rule.setContent {
            Themed(dark = false) {
                val id = editing
                if (id == null) {
                    LiveEditor(f) {
                        ThemeControls(BuiltInThemes.ALL, BuiltInThemes.DEFAULT.id, false, {}, { editing = it })
                    }
                } else {
                    // Edit theme… navigates to the theme editor, which opens on its Type tab.
                    ThemeEditorScreen(ThemeEditorUiState(theme = BuiltInThemes.byId(id)!!), {})
                }
            }
        }
        val taps = TapCounter(rule)
        taps.tap(MORE)
        taps.tap("View")
        taps.tap("Edit theme…")
        assertEquals(BuiltInThemes.DEFAULT.id, editing)
        // The custom theme's fonts are the first rows there: no further tap to see them.
        for (row in listOf("Body font", "Heading font", "Code font (inside preview)")) {
            rule.onNodeWithText(row).assertIsDisplayed()
        }
        assertEquals(2, taps.afterMenu())
    }

    @Test
    fun `PDF export is one tap after the menu`() {
        val (f, taps) = editor()
        val printed = ArrayList<String>()
        val page =
            object : PrintTarget {
                override val isOnScreen = true

                override suspend fun print(jobName: String): PrintOutcome {
                    printed += jobName
                    return PrintOutcome.Opened
                }
            }
        f.vm.preview.attachPage(page) {}
        taps.tap(MORE)
        taps.tap("Export PDF")
        assertEquals("the page is asked to print this file", listOf("notes"), printed)
        assertEquals(1, taps.afterMenu())
    }

    @Test
    fun `Mermaid export is two taps after the menu`() {
        val (f, taps) = editor("# D\n\n```mermaid\ngraph LR\n  A --> B\n```\n")
        taps.tap(MORE)
        taps.tap("Export images…")
        assertTrue(f.vm.uiState.value.overlay is EditorOverlay.ExportImages)
        taps.tap("Export 1 image")
        // The export is under way. Its bytes come from the WebView, which a host
        // test has not got; the PNG and SVG themselves are proven in Playwright
        // (render.test.ts, "exports a diagram as SVG and as a 2x PNG").
        val sheet = f.vm.uiState.value.overlay as EditorOverlay.ExportImages
        assertTrue("Export did not start", sheet.state.running)
        assertEquals(1, sheet.state.count)
        assertEquals(2, taps.afterMenu())
    }

    // The Navigator: tap the title, type 2-3 letters, tap the result.

    @Test
    fun `a heading is three interactions through the Navigator`() {
        val text = "# Handbook\n\n" + (1..40).joinToString("") { "Paragraph $it.\n\n" } + "## Installing\n\nSteps.\n"
        val (f, taps) = editor(text, "handbook.md")
        val heading = text.lines().indexOf("## Installing")
        taps.tap("handbook.md")
        rule.onNode(hasSetTextAction()).performTextInput("ins")
        taps.tap("Installing")
        assertEquals(heading, f.vm.anchorLine.value)
        assertEquals(null, f.vm.uiState.value.navigator)
        assertEquals(3, taps.taps + 1)
    }

    @Test
    fun `a recent file is three interactions through the Navigator`() {
        val f = EditorFixture(temp.root, dispatcher)
        val roadmap = f.file("roadmap.md", "# Roadmap\n")
        f.vm.openDocument(roadmap)
        f.vm.openDocument(f.file("notes.md", "# Notes\n"))
        rule.setContent { Themed(dark = false) { LiveEditor(f) } }
        val taps = TapCounter(rule)
        taps.tap("notes.md")
        rule.onNode(hasSetTextAction()).performTextInput("ro")
        taps.tap("roadmap.md")
        assertEquals(
            roadmap,
            f.vm.active
                ?.open
                ?.uri
                ?.value,
        )
        assertEquals(3, taps.taps + 1)
    }

    // Share in: the sheet over the sending app (ShareActivity), no full app.

    /**
     * @param directShare the file was chosen in Android's share sheet itself
     *   (a Direct Share target, ADR 0017), so it arrives preselected.
     */
    private fun share(
        remembered: Boolean,
        directShare: Boolean = false,
    ): Pair<EditorFixture, TapCounter> {
        val f = EditorFixture(temp.root, dispatcher)
        val notes = f.file("notes.md", "# Notes\n")
        val other = f.file("other.md", "# Other\n")
        f.settings.update {
            it.copy(
                recentFiles =
                    listOf(
                        RecentFile(other, "other.md", "Documents", 1),
                        RecentFile(notes, "notes.md", "Documents", 0),
                    ),
                share = it.share.copy(lastTarget = if (remembered) notes else other),
            )
        }
        val vm = ShareViewModel(f.store, f.drafts, f.history, f.settings, f.vm, f.scope) { "1 Nov 2026" }
        val preselect =
            if (directShare) {
                val grants = mapOf(DocumentUri(notes) to true, DocumentUri(other) to true)
                DirectShareTargets(NoShortcuts, f.settings, { grants }, f.scope, dispatcher)
                    .resolve(DirectShareIds.of(notes))
            } else {
                null
            }
        vm.begin("A snippet from Chrome", "example.org", preselect)
        rule.setContent {
            Themed(dark = false) {
                val state by vm.state.collectAsState()
                ShareSheet(state) { if (it != ShareAction.Cancel && it != ShareAction.OpenResult) vm.onAction(it) }
            }
        }
        val taps = TapCounter(rule)
        if (!remembered && !directShare) taps.tap("notes.md")
        taps.tap("Append")
        rule.waitUntil(TIMEOUT) { vm.state.value.phase is SharePhase.Done }
        assertTrue(f.disk("notes.md").endsWith("A snippet from Chrome\n"))
        assertEquals("# Other\n", f.disk("other.md"))
        return f to taps
    }

    @Test
    fun `sharing into the last-used file is one tap in the sheet, two in all`() {
        val (_, taps) = share(remembered = true)
        assertEquals(1, taps.taps)
        assertEquals(2, CHOOSER_TAPS + taps.taps)
    }

    @Test
    fun `sharing into any other recent file is two taps in the sheet, three in all`() {
        // Every recent file is a row in the sheet itself (no picker), so
        // choosing one is a single tap, then Append.
        val (_, taps) = share(remembered = false)
        assertEquals(2, taps.taps)
        assertEquals(3, CHOOSER_TAPS + taps.taps)
    }

    @Test
    fun `sharing into any recent file through a Direct Share target is one tap in the sheet, two in all`() {
        // The file is the target tapped in Android's own share sheet, so ours
        // opens with it chosen; Append is still a tap of its own (ADR 0017).
        val (_, taps) = share(remembered = false, directShare = true)
        assertEquals(1, taps.taps)
        assertEquals(2, CHOOSER_TAPS + taps.taps)
    }

    /** No shortcut store: the audit only resolves an id. */
    private object NoShortcuts : ShortcutPublisher {
        override val maxPerActivity = 4

        override fun current() = emptyList<ShortcutSpec>()

        override fun push(spec: ShortcutSpec) = true

        override fun remove(ids: List<String>) = Unit

        override fun reportUsed(id: String) = Unit
    }

    /** A folder whose listing is [rows]; nothing in it can be renamed or created. */
    private class OneListFolder(
        private val rows: List<FolderRow>,
    ) : FolderSource {
        override fun list(
            treeUri: String,
            store: DocumentStore?,
            showAllFiles: Boolean,
        ) = ProviderResult.Ok(rows.toList())

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

    private companion object {
        const val MORE = "More"
        const val TREE = "content://tree/notes"

        /**
         * SS Markdown in the system share sheet: the one tap Android owns. As
         * J9 counts it (Share → SS Markdown → Append, "2–3 taps"), the count
         * starts once the sending app's Share is open; counting that Share
         * button too adds one to each total.
         */
        const val CHOOSER_TAPS = 1
        const val TIMEOUT = 5_000L
    }
}
