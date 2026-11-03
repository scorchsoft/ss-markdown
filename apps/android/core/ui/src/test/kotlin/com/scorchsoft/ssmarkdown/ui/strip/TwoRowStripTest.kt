package com.scorchsoft.ssmarkdown.ui.strip

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import com.scorchsoft.ssmarkdown.core.ui.SSMarkdownTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The two-row strip (Settings → Formatting bar), measured as drawn. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp")
class TwoRowStripTest {
    private val activity = ComposeActivityRule()

    @get:Rule val rules = activity.chain
    private val rule get() = activity.compose

    private val keys = mutableListOf<StripKey>()

    private fun show(
        state: FormattingStripState,
        fontScale: Float = 1f,
    ) = rule.setContent {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            SSMarkdownTheme(darkTheme = false) {
                FormattingStrip(state, onKey = { keys += it }, onSymbol = {}, onCursor = {}, rows = StripRows.Two)
            }
        }
    }

    private fun node(label: String) = rule.onNodeWithContentDescription(label).fetchSemanticsNode()

    private fun dp(px: Float) = px / rule.density.density

    private fun top(label: String) = dp(node(label).positionInRoot.y)

    private val rootWidth get() =
        rule
            .onRoot()
            .fetchSemanticsNode()
            .size.width
            .toFloat()

    /** Whole and on screen: its unclipped box lies inside the strip. */
    private fun SemanticsNode.fullyShown() =
        positionInRoot.x >= -0.5f && positionInRoot.x + size.width <= rootWidth + 0.5f

    private val codeLabels =
        listOf(
            "Block style",
            "Bold",
            "Italic",
            "Bulleted list",
            "Numbered list",
            "Task",
            "Code",
            "Link",
            "Outdent",
            "Indent",
            "Table",
            "Insert image",
            "Quote",
        )

    @Test
    fun everyCodeKeyShowsAt412DpWithThePinnedGroupInTheTopRow() {
        show(FormattingStripState(canUndo = true))
        assertEquals(96f, dp(node("Formatting").size.height.toFloat()), 0.5f)
        for (label in codeLabels) assertTrue("$label is cut or off screen", node(label).fullyShown())
        // The top row runs Block style … Numbered list, then Undo, Redo and { } at its right end.
        for (label in listOf("Block style", "Numbered list", "Undo", "Redo", "Symbols")) {
            assertEquals(label, 0f, top(label), 0.5f)
        }
        assertEquals(node("Undo").positionInRoot.x + node("Undo").size.width, node("Redo").positionInRoot.x, 0.5f)
        assertEquals(rootWidth, node("Symbols").let { it.positionInRoot.x + it.size.width }, 0.5f)
        // The bottom row carries on in the same order from the left edge, Outdent and Indent side by side.
        assertEquals(48f, top("Task"), 0.5f)
        assertEquals(0f, node("Task").positionInRoot.x, 0.5f)
        assertEquals(48f, top("Quote"), 0.5f)
        assertEquals(
            node("Outdent").positionInRoot.x + node("Outdent").size.width,
            node("Indent").positionInRoot.x,
            0.5f,
        )
        // Keys stay 48 dp, so the two rows line up as columns.
        assertEquals(node("Bold").positionInRoot.x, node("Code").positionInRoot.x, 0.5f)
    }

    @Test
    fun theSymbolsPageShowsMoreSymbolsAcrossTwoRows() {
        show(FormattingStripState(page = StripPage.Symbols))
        for (label in listOf("Backtick", "Exclamation mark", "Cursor left", "Cursor right")) {
            assertTrue("$label is cut or off screen", node(label).fullyShown())
        }
        assertEquals(48f, top("Cursor right"), 0.5f)
    }

    @Test
    fun tableKeysKeepTheirPlaceBeforeUndoInTheTopRow() {
        show(FormattingStripState(canUndo = true, inTable = true))
        for (label in listOf("Table actions", "Previous cell", "Next cell", "Undo")) {
            assertEquals(label, 0f, top(label), 0.5f)
        }
        assertEquals(node("Next cell").let { it.positionInRoot.x + it.size.width }, node("Undo").positionInRoot.x, 0.5f)
        node("Outdent").let { assertEquals(48f, dp(it.positionInRoot.y), 0.5f) }
    }

    @Test
    @Config(qualifiers = "w360dp-h780dp")
    fun at360DpTheLastKeyIsCutAndAScrollAway() {
        show(FormattingStripState())
        for (label in codeLabels.dropLast(2)) assertTrue("$label is cut or off screen", node(label).fullyShown())
        val image = node("Insert image")
        val shown = (rootWidth - image.positionInRoot.x) / image.size.width
        assertEquals(0.5f, shown, 0.02f)
        rule.onNodeWithContentDescription("Quote").performScrollTo().performClick()
        assertEquals(listOf(StripKey.Quote), keys)
    }

    @Test
    fun aLargeFontKeepsTwoRowsOf48DpKeys() {
        show(FormattingStripState(), fontScale = 2f)
        assertEquals(96f, dp(node("Formatting").size.height.toFloat()), 0.5f)
        assertEquals(48f, dp(node("Bold").size.width.toFloat()), 0.5f)
        assertEquals(48f, top("Outdent"), 0.5f)
    }

    @Test
    @Config(qualifiers = "w800dp-h1280dp")
    fun keysThatAllFitInTheTopRowStayOneRow() {
        show(FormattingStripState(mode = StripMode.Preview))
        assertEquals(48f, dp(node("Formatting").size.height.toFloat()), 0.5f)
    }

    @Test
    fun talkBackReadsTheTopRowThenTheBottomRowAsOneGroup() {
        show(FormattingStripState(canUndo = true))
        val strip = node("Formatting")
        assertEquals(true, strip.config.getOrNull(SemanticsProperties.IsTraversalGroup))
        // The strip's parts, in reading order: top row keys, pinned group, bottom row.
        val order =
            strip.children
                .sortedBy { it.config.getOrNull(SemanticsProperties.TraversalIndex) ?: 0f }
                .map { part -> part.allLabels() }
                .filter { it.isNotEmpty() }
        assertEquals(listOf("Block style", "Bold", "Italic", "Bulleted list", "Numbered list"), order[0])
        assertEquals(listOf("Undo", "Redo", "Symbols"), order[1])
        assertEquals(listOf("Task", "Code", "Link", "Outdent", "Indent", "Table", "Insert image", "Quote"), order[2])
    }

    private fun SemanticsNode.allLabels(): List<String> =
        (config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() + children.flatMap { it.allLabels() })
}
