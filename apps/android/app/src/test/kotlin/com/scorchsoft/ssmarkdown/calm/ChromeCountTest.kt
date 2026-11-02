package com.scorchsoft.ssmarkdown.calm

import android.view.View
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.scorchsoft.ssmarkdown.editor.EditorUiState
import com.scorchsoft.ssmarkdown.editor.ViewMode
import com.scorchsoft.ssmarkdown.fixtures.Samples
import com.scorchsoft.ssmarkdown.folder.FolderEntryRow
import com.scorchsoft.ssmarkdown.folder.FolderUiState
import com.scorchsoft.ssmarkdown.ui.EditorFor
import com.scorchsoft.ssmarkdown.ui.Themed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * Rubric #2, Calm: "At rest only two bars are visible. No control appears
 * until it applies (folder button, zoom chip, Save all, formatting strip).
 * There are no dialogs on the happy path." Every state the rubric names is
 * rendered here and its visible chrome counted: each control a finger can
 * tap, by its TalkBack name, and the bars they sit in (controls whose
 * centres share a row). The expectations are docs/ux-spec.md's "Chrome".
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-420dpi")
class ChromeCountTest {
    @get:Rule val rule = createComposeRule()

    private lateinit var view: View

    private fun show(
        state: EditorUiState,
        fontScale: Float = 1f,
    ) {
        rule.setContent {
            view = LocalView.current
            Themed(dark = false, fontScale) { EditorFor(state) }
        }
    }

    /** The keyboard comes up, as the system reports it. */
    private fun raiseKeyboard() {
        rule.runOnIdle {
            val insets =
                WindowInsetsCompat
                    .Builder()
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, KEYBOARD_PX))
                    .setVisible(WindowInsetsCompat.Type.ime(), true)
                    .build()
            ViewCompat.dispatchApplyWindowInsets(view, insets)
        }
        rule.waitForIdle()
    }

    private data class Control(
        val label: String,
        val centreY: Float,
        val left: Float,
    )

    /** Every visible tappable control, top to bottom, named as TalkBack names it; the title is "Title". */
    private fun controls(): List<Control> =
        rule
            .onAllNodes(hasClickAction())
            .fetchSemanticsNodes()
            .filter { it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f }
            .map { node ->
                val label =
                    node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
                        ?: node.config
                            .getOrNull(SemanticsProperties.Text)
                            ?.joinToString { it.text }
                            .orEmpty()
                val title = Samples.saved.title
                val name = if (label.startsWith(title) || label.startsWith("invoice")) TITLE else label
                Control(name, node.boundsInRoot.center.y, node.boundsInRoot.left)
            }.let { all ->
                // Reading order: bar by bar, then left to right.
                val rowGap = with(rule.density) { ROW_GAP_DP * density }
                all.sortedWith(compareBy({ (it.centreY / rowGap).toInt() }, { it.left }))
            }

    /** Rows of controls: what the eye reads as a bar. */
    private fun bars(controls: List<Control>): Int {
        val rowGap = with(rule.density) { ROW_GAP_DP * density }
        var bars = 0
        var last = Float.NEGATIVE_INFINITY
        for (c in controls) {
            if (abs(c.centreY - last) > rowGap) bars++
            last = c.centreY
        }
        return bars
    }

    private fun labels() = controls().map { it.label }

    private fun assertNoDialog() {
        assertTrue("a dialog is up", rule.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty())
        assertTrue("a popup is up", rule.onAllNodes(isPopup()).fetchSemanticsNodes().isEmpty())
    }

    private fun assertRest(expected: List<String>) {
        val controls = controls()
        assertEquals(expected, controls.map { it.label })
        assertEquals("bars at rest", 2, bars(controls))
        assertNoDialog()
    }

    // At rest: two bars, seven controls, in every mode.

    @Test
    fun `at rest in Code there are two bars and seven controls`() {
        show(Samples.saved)
        assertRest(REST)
    }

    @Test
    fun `at rest in Split there are two bars and seven controls`() {
        show(Samples.saved.copy(mode = ViewMode.Split))
        assertRest(REST)
    }

    @Test
    fun `at rest in Preview there are two bars and seven controls`() {
        show(Samples.saved.copy(mode = ViewMode.Preview))
        assertRest(REST)
    }

    @Test
    fun `unsaved changes the Save control, never adds one`() {
        show(Samples.unsaved)
        assertRest(listOf(TITLE, "Save", COPY, MORE, CODE, SPLIT, PREVIEW))
    }

    // Typing: the strip replaces the mode bar; still two bars.

    @Test
    fun `typing with the keyboard up swaps the mode bar for the strip, still two bars`() {
        show(Samples.unsaved)
        assertFalse("the strip shows at rest", "Bold" in labels())
        raiseKeyboard()
        val controls = controls()
        val labels = controls.map { it.label }
        assertEquals(listOf(TITLE, "Save", COPY, MORE), labels.take(4))
        assertTrue("Bold" in labels && "Undo" in labels)
        assertFalse("the mode bar stays with the keyboard up", SPLIT in labels || PREVIEW in labels)
        assertEquals("bars while typing", 2, bars(controls))
        assertNoDialog()
    }

    // Folder mode: ≡ appears; Save all only while something is unsaved.

    private fun folder(dirty: Boolean) =
        FolderUiState(
            "product-docs",
            "t",
            listOf(FolderEntryRow("a", "notes.md", 0, false, dirty = dirty)),
            1,
            unsavedCount = if (dirty) 1 else 0,
        )

    @Test
    fun `folder mode adds only the folder button`() {
        show(Samples.saved.copy(folder = folder(dirty = false)))
        assertRest(listOf(OPEN_FOLDER, TITLE, ALL_SAVED, COPY, MORE, CODE, SPLIT, PREVIEW))
    }

    @Test
    fun `folder mode with unsaved files adds no Save all to the bars`() {
        show(Samples.unsaved.copy(folder = folder(dirty = true)))
        assertRest(listOf(OPEN_FOLDER, TITLE, "Save", COPY, MORE, CODE, SPLIT, PREVIEW))
    }

    @Test
    fun `the drawer offers Save all only while a file is unsaved`() {
        show(Samples.unsaved.copy(folder = folder(dirty = true).copy(drawerOpen = true)))
        assertTrue(SAVE_ALL in labels())
    }

    @Test
    fun `a clean folder's drawer has no Save all`() {
        show(Samples.saved.copy(folder = folder(dirty = false).copy(drawerOpen = true)))
        assertFalse(SAVE_ALL in labels())
    }

    @Test
    @Config(qualifiers = "w900dp-h1200dp-320dpi")
    fun `docked, Save all appears with the first unsaved file and goes with the last`() {
        show(Samples.unsaved.copy(folder = folder(dirty = true)))
        assertTrue(SAVE_ALL in labels())
    }

    @Test
    @Config(qualifiers = "w900dp-h1200dp-320dpi")
    fun `docked and clean, there is no Save all`() {
        show(Samples.saved.copy(folder = folder(dirty = false)))
        assertFalse(SAVE_ALL in labels())
        assertNoDialog()
    }

    // Zoom: the chip only while this file differs from the default.

    @Test
    fun `zoomed, the zoom chip appears in its pane`() {
        show(Samples.saved.copy(codeZoom = 120))
        val labels = labels()
        assertEquals(1, labels.count { it.startsWith(RESET_ZOOM) })
        assertEquals(REST.size + 1, labels.size)
    }

    @Test
    fun `a file at the default zoom shows no chip, even when that default is not 100 percent`() {
        show(Samples.saved.copy(codeZoom = 120, defaultCodeZoom = 120, previewZoom = 90, defaultPreviewZoom = 90))
        assertRest(REST)
    }

    @Test
    fun `zoomed in Preview, the chip is the Preview pane's`() {
        show(Samples.saved.copy(mode = ViewMode.Preview, previewZoom = 120))
        assertEquals(1, labels().count { it.startsWith(RESET_ZOOM) })
    }

    // Read-only: the same seven controls; Save becomes Save as…

    @Test
    fun `read-only shows the same seven controls, Save as in Save's place`() {
        show(Samples.readOnlyClean)
        assertRest(listOf(TITLE, SAVE_AS, COPY, MORE, CODE, SPLIT, PREVIEW))
    }

    @Test
    fun `read-only with edits is still seven controls`() {
        show(Samples.readOnly)
        assertRest(listOf(TITLE, SAVE_AS, COPY, MORE, CODE, SPLIT, PREVIEW))
    }

    // The folder header at 360 dp and 2× font: the title keeps priority.

    @Test
    @Config(qualifiers = "w360dp-h780dp-420dpi")
    fun `on a 360 dp phone in folder mode at double font the title keeps more than a stub`() {
        show(Samples.readOnly.copy(folder = folder(dirty = true)), fontScale = 2f)
        val node = rule.onNode(hasText("invoice-notes.md"), useUnmergedTree = true).fetchSemanticsNode()
        val results = ArrayList<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        val shown = results.single().getLineEnd(0, visibleEnd = true)
        assertTrue("only $shown characters of the name show", shown >= MIN_TITLE_CHARS)
        assertNoDialog()
    }

    private companion object {
        const val KEYBOARD_PX = 900
        const val ROW_GAP_DP = 24
        const val MIN_TITLE_CHARS = 6
        const val TITLE = "Title"
        const val COPY = "Copy all"
        const val ALL_SAVED = "All saved"
        const val MORE = "More"
        const val CODE = "Code"
        const val SPLIT = "Split"
        const val PREVIEW = "Preview"
        const val OPEN_FOLDER = "Open folder"
        const val SAVE_ALL = "Save all"
        const val SAVE_AS = "Save as…"
        const val RESET_ZOOM = "Reset zoom"
        val REST = listOf(TITLE, ALL_SAVED, COPY, MORE, CODE, SPLIT, PREVIEW)
    }
}
