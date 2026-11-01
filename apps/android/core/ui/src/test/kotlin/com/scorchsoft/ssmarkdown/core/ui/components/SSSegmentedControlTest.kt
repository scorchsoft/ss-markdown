package com.scorchsoft.ssmarkdown.core.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.scorchsoft.ssmarkdown.core.ui.SSMarkdownTheme
import com.scorchsoft.ssmarkdown.ui.strip.ComposeActivityRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * UX-8: segments size to their labels and the room, grow with the font, and
 * stack when they can't fit. Native graphics, because legacy Robolectric
 * measures every string as zero wide.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp")
class SSSegmentedControlTest {
    private val activity = ComposeActivityRule()

    @get:Rule val rules = activity.chain
    private val rule get() = activity.compose

    private var chosen = -1

    private fun show(
        options: List<String>,
        fontScale: Float = 1f,
        width: Dp? = null,
        fill: Boolean = false,
    ) = rule.setContent {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            SSMarkdownTheme(darkTheme = false) {
                Box(if (width != null) Modifier.width(width) else Modifier.padding(horizontal = 16.dp)) {
                    SSSegmentedControl(
                        options = options,
                        selectedIndex = 0,
                        onSelect = { chosen = it },
                        modifier = if (fill) Modifier.fillMaxWidth() else Modifier,
                    )
                }
            }
        }
    }

    @Test
    fun `four theme-editor tabs fit a phone without clipping`() {
        // 4 x 104 dp overflowed the 380 dp a phone gives; now they share it.
        show(listOf("Type", "Colours", "Spacing", "JSON"))
        val right = rule.onNodeWithText("JSON").getBoundsInRoot().right
        assertTrue("JSON ends at $right", right <= 396.dp)
        rule.onNodeWithText("JSON").performClick()
        assertEquals(3, chosen)
    }

    @Test
    fun `a full-width control shares the width rather than leaving half the track empty`() {
        show(listOf("Light", "Dark"), width = 380.dp, fill = true)
        val dark = rule.onNodeWithText("Dark").getBoundsInRoot()
        assertTrue("Dark is centred at ${(dark.left + dark.right) / 2}", (dark.left + dark.right) / 2 > 260.dp)
    }

    @Test
    fun `at double font size long labels stack as radio rows`() {
        show(listOf("Reopen last file", "New scratch note"), fontScale = 2f, width = 380.dp)
        rule.onNodeWithText("Reopen last file").assertIsSelected()
        val second = rule.onNodeWithText("New scratch note")
        second.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        second.assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, chosen)
        // Stacked: the second option sits below the first, not beside it.
        val first = rule.onNodeWithText("Reopen last file").getBoundsInRoot()
        assertTrue(second.getBoundsInRoot().top >= first.bottom)
    }

    @Test
    fun `segments grow taller with the font rather than clipping`() {
        show(listOf("Code", "Split", "Preview"), fontScale = 1.3f)
        rule.onNodeWithText("Preview").assertHeightIsAtLeast(44.dp)
    }

    @Test
    fun `sizing rules`() {
        val phone = 380.dp
        assertEquals(104.dp, SegmentSizing.of(3, phone, 60.dp, fill = false).segmentWidth)
        val four = SegmentSizing.of(4, phone, 80.dp, fill = false)
        assertFalse(four.stacked)
        assertTrue(four.segmentWidth * 4 < phone)
        assertTrue(SegmentSizing.of(2, phone, 200.dp, fill = false).stacked)
        assertTrue(SegmentSizing.of(2, phone, 60.dp, fill = true).segmentWidth > 180.dp)
        assertEquals(130.dp, SegmentSizing.of(3, Dp.Infinity, 130.dp, fill = false).segmentWidth)
    }
}
