package com.scorchsoft.ssmarkdown.editor

import com.scorchsoft.ssmarkdown.fixtures.EditorFixture
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The header's reading line is the leading pane's scroll position over its
 * scrollable range (ux-spec "Where am I?"). It used to be the top line over
 * the line count, which can never reach the end: the last screen's lines
 * are never at the top (docs/edge-cases.md, "Reading line stops short").
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReadingLineTest {
    @get:Rule val temp = TemporaryFolder()

    private val f by lazy { EditorFixture(temp.root, UnconfinedTestDispatcher()).apply { vm.start() } }

    private val shown get() = f.vm.uiState.value.readingFraction

    @Test
    fun `the Code pane at the very bottom fills the line, at the top it is empty`() {
        f.vm.onScrollFractionChanged(1f)
        assertEquals(1f, shown)
        f.vm.onScrollFractionChanged(0.5f)
        assertEquals(0.5f, shown)
        f.vm.onScrollFractionChanged(0f)
        assertEquals(0f, shown)
    }

    @Test
    fun `only the pane on screen moves the line, and switching modes shows the other pane's`() {
        f.vm.onScrollFractionChanged(0.25f)
        f.vm.onPaneScrollFraction(Pane.Preview, 1f)
        assertEquals("Code is on screen", 0.25f, shown)

        f.vm.onAction(EditorAction.SelectMode(ViewMode.Preview))
        assertEquals("Preview is on screen now", 1f, shown)
        f.vm.onPaneScrollFraction(Pane.Preview, 0f)
        assertEquals(0f, shown)
    }

    @Test
    fun `in Split the pane last touched leads`() {
        f.vm.onAction(EditorAction.SelectMode(ViewMode.Split))
        f.vm.onScrollFractionChanged(0.75f)
        f.vm.onPaneScrollFraction(Pane.Preview, 0.1f)
        assertEquals("Code leads until the preview is touched", 0.75f, shown)
        f.vm.onAction(EditorAction.PaneTouched(Pane.Preview))
        assertEquals(0.1f, shown)
    }

    @Test
    fun `out-of-range reports are clamped`() {
        f.vm.onScrollFractionChanged(3f)
        assertEquals(1f, shown)
        f.vm.onScrollFractionChanged(-1f)
        assertEquals(0f, shown)
    }
}
