package com.scorchsoft.ssmarkdown.core.ui.components

import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onParent
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
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

/**
 * The fast-scroll thumb (docs/ux-spec.md, "Fast scroll"): when it shows
 * and hides, what a drag reports, the section bubble and its tick, the
 * back-gesture exclusion, and that the faded edge passes taps through.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w400dp-h800dp")
class FastScrollThumbTest {
    private val activity = ComposeActivityRule()

    @get:Rule val rules = activity.chain
    private val rule get() = activity.compose

    private val state = FastScrollState()
    private val drags = ArrayList<Float>()
    private val ticks = ArrayList<HapticFeedbackType>()
    private var taps = 0
    private var starts = 0
    private var ends = 0

    private val haptics =
        object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                ticks += hapticFeedbackType
            }
        }

    private fun show(animate: Boolean = true) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            SSMarkdownTheme(darkTheme = false) {
                CompositionLocalProvider(LocalHapticFeedback provides haptics) {
                    Box(Modifier.size(PANE_W.dp, PANE_H.dp).clickable { taps++ }.fastScrollTouches(state)) {
                        FastScrollThumb(
                            state,
                            onDrag = { drags += it },
                            onDragStart = { starts++ },
                            onDragEnd = { ends++ },
                            animate = animate,
                        )
                    }
                }
            }
        }
        frame()
    }

    /** A finger lands on the pane (arming the thumb), the pane scrolls, and the finger lifts. */
    private fun userScrolls(
        fraction: Float = 0.3f,
        visible: Float = LONG,
    ) {
        state.onTouch(true)
        state.onScrolled(fraction, visible)
        state.onTouch(false)
        frame()
    }

    /** State written outside composition reaches it, then one frame runs. */
    private fun frame() {
        Snapshot.sendApplyNotifications()
        rule.mainClock.advanceTimeByFrame()
    }

    private fun advance(millis: Long) {
        Snapshot.sendApplyNotifications()
        rule.mainClock.advanceTimeBy(millis)
    }

    private fun thumbShown() = tagged(FastScrollDefaults.THUMB_TAG)

    private fun tagged(tag: String) =
        rule
            .onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, tag), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isNotEmpty()

    @Test
    fun `scrolling a long document shows the thumb, and it hides a second and a half after scrolling stops`() {
        show()
        assertFalse("nothing at rest", thumbShown())
        userScrolls()
        assertTrue(state.isShown)
        assertTrue(thumbShown())

        advance(1_000)
        assertTrue("still there after a second", state.isShown)
        state.onScrolled(0.4f, LONG) // a fling still moving: the clock starts again
        advance(1_000)
        assertTrue(state.isShown)
        advance(700)
        assertFalse("hidden 1.5 s after the last scroll", state.isShown)
        advance(500)
        assertFalse("and gone once faded", thumbShown())
    }

    @Test
    fun `a short document never shows it`() {
        show()
        userScrolls(visible = 0.3f)
        assertFalse("about three screens: no thumb", state.isShown)
        assertFalse(thumbShown())
    }

    @Test
    fun `a scroll nobody touched for, such as Split following or a Navigator jump, does not show it`() {
        show()
        state.onScrolled(0.5f, LONG)
        frame()
        assertFalse(state.isShown)
    }

    @Test
    fun `selection handles in use keep it away`() {
        show()
        userScrolls()
        assertTrue(state.isShown)
        state.suppressed = true
        assertFalse(state.isShown)
        userScrolls()
        assertFalse("not while the handles are in use", state.isShown)
        state.suppressed = false
        userScrolls()
        assertTrue(state.isShown)
    }

    @Test
    fun `dragging reports the fraction under the finger, monotonic, and widens nothing else`() {
        show()
        userScrolls(fraction = 0f)
        val thumb = rule.onNodeWithTag(FastScrollDefaults.THUMB_TAG, useUnmergedTree = true)
        thumb.assertIsDisplayed()
        val touch = with(rule.density) { FastScrollDefaults.Touch.toPx() }
        val track = with(rule.density) { PANE_H.dp.toPx() } - touch
        thumb.performTouchInput {
            down(center)
            for (i in 1..STEPS) moveTo(center.copy(y = center.y + track * i / STEPS))
        }
        frame()
        assertTrue("held", state.isDragging)
        assertEquals(1, starts)
        assertTrue("several moves reported: $drags", drags.size >= STEPS / 2)
        assertTrue("monotonic: $drags", drags.zipWithNext().all { (a, b) -> b >= a })
        assertEquals("the bottom of the travel is the end of the document", 1f, drags.last(), 0.02f)

        // Held well past the hide delay: it stays.
        advance(3_000)
        assertTrue(state.isShown)

        rule.onNodeWithTag(FastScrollDefaults.THUMB_TAG, useUnmergedTree = true).performTouchInput { up() }
        frame()
        assertFalse(state.isDragging)
        assertEquals(1, ends)
        assertEquals("the thumb stays where it was let go", 1f, state.fraction, 0.02f)
        assertEquals("the drag never reached the pane as a tap", 0, taps)
    }

    @Test
    fun `while held, the bubble names the section and a change of section ticks`() {
        show()
        userScrolls()
        state.setSection(0, "Introduction")
        rule.onNodeWithTag(FastScrollDefaults.THUMB_TAG, useUnmergedTree = true).performTouchInput { down(center) }
        frame()
        rule.onNodeWithText("Introduction", useUnmergedTree = true).assertIsDisplayed()
        assertTrue("no tick for the section it started in", ticks.isEmpty())

        state.setSection(1, "Install")
        frame()
        rule.onNodeWithText("Install", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(listOf(HapticFeedbackType.SegmentTick), ticks)

        state.setSection(1, "Install")
        frame()
        assertEquals("the same section again is not a change", 1, ticks.size)

        // The bubble sits beside the thumb, to its left, never over it.
        val bubble = rule.onNodeWithTag(FastScrollDefaults.BUBBLE_TAG, useUnmergedTree = true).getBoundsInRoot()
        val thumb = rule.onNodeWithTag(FastScrollDefaults.THUMB_TAG, useUnmergedTree = true).getBoundsInRoot()
        assertTrue("bubble $bubble left of thumb $thumb", bubble.right <= thumb.left)
    }

    @Test
    fun `no section is no bubble, and a change while not held never ticks`() {
        show()
        userScrolls()
        state.setSection(3, "Elsewhere")
        frame()
        assertTrue(ticks.isEmpty())
        state.setSection(FastScrollState.NO_SECTION, null)
        rule.onNodeWithTag(FastScrollDefaults.THUMB_TAG, useUnmergedTree = true).performTouchInput { down(center) }
        frame()
        assertFalse("no bubble", tagged(FastScrollDefaults.BUBBLE_TAG))
    }

    @Test
    fun `its target is excluded from the back gesture while shown, within the 200 dp cap, and not when hidden`() {
        show()
        val view = composeView()
        assertTrue("nothing excluded at rest", view.systemGestureExclusionRects.isEmpty())
        userScrolls()
        frame()
        val rects = view.systemGestureExclusionRects
        assertEquals(1, rects.size)
        val cap = with(rule.density) { 200.dp.toPx() }
        assertTrue("${rects[0]} within the cap", rects[0].height() <= cap)
        assertEquals("the right edge", view.width, rects[0].right)

        state.hide()
        advance(500)
        assertTrue("cleared once hidden", view.systemGestureExclusionRects.isEmpty())
    }

    @Test
    fun `a tap on the faded edge reaches the text, and one on the shown thumb does not`() {
        show()
        val edge = rule.onRoot()
        edge.performTouchInput { click(centerRight.copy(x = width - 10f, y = 40f)) }
        frame()
        assertEquals("hidden: the tap went through", 1, taps)

        userScrolls(fraction = 0f)
        edge.performTouchInput { click(centerRight.copy(x = width - 10f, y = 40f)) }
        frame()
        assertEquals("shown: the thumb took it", 1, taps)
    }

    @Test
    fun `TalkBack never meets it`() {
        show()
        userScrolls()
        rule
            .onNodeWithTag(FastScrollDefaults.THUMB_TAG, useUnmergedTree = true)
            .onParent()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility))
    }

    @Test
    fun `with animations removed it shows and hides without a fade`() {
        show(animate = false)
        userScrolls()
        assertTrue(thumbShown())
        state.hide()
        frame()
        assertFalse("gone on the next frame, no fade", thumbShown())
    }

    private fun onRootView(): View = rule.activity.findViewById<ViewGroup>(android.R.id.content)

    /** The view the composition draws in: where the exclusion rects are set. */
    private fun composeView(): View {
        var view: View = onRootView()
        while (view is ViewGroup && view.childCount > 0 && view.javaClass.simpleName != "AndroidComposeView") {
            view = view.getChildAt(0)
        }
        return view
    }

    private companion object {
        const val PANE_W = 360
        const val PANE_H = 640
        const val LONG = 0.05f
        const val STEPS = 20
    }
}
