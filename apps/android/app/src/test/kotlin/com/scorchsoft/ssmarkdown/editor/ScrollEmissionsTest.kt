package com.scorchsoft.ssmarkdown.editor

import com.scorchsoft.ssmarkdown.fixtures.EditorFixture
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Scrolling moves the anchor on nearly every frame of a fling; the screen
 * state, whose every emission recomposes the whole screen, changes only
 * when the breadcrumb or the reading line visibly does (performance
 * review PERF-13). Counts emissions, not time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScrollEmissionsTest {
    @get:Rule val temp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()

    @Test
    fun `a fling through one section of a long file barely touches the screen state`() {
        val f = EditorFixture(temp.root, dispatcher)
        val text = (0 until SECTIONS).joinToString("") { "# Section $it\n" + "text\n".repeat(LINES_PER_SECTION) }
        f.vm.openDocument(f.file("long.md", text))
        dispatcher.scheduler.advanceUntilIdle()
        var emissions = 0
        var last = f.vm.uiState.value
        for (line in FIRST until FIRST + SCROLLED) {
            f.vm.onPaneTopLine(Pane.Code, line)
            val now = f.vm.uiState.value
            if (now !== last) emissions++
            last = now
        }
        assertEquals(listOf("Section 10"), last.breadcrumb)
        // Before: one emission per line (the reading fraction changed with every line), 1,000 here.
        assertTrue("$emissions emissions for $SCROLLED lines", emissions <= SCROLLED * READING_STEPS / TOTAL + 2)
        assertEquals(FIRST + SCROLLED - 1, f.vm.anchorLine.value)
    }

    private companion object {
        const val SECTIONS = 50
        const val LINES_PER_SECTION = 2_000
        const val TOTAL = SECTIONS * (LINES_PER_SECTION + 1) + 1
        const val FIRST = 10 * (LINES_PER_SECTION + 1) + 1
        const val SCROLLED = 1_000
        const val READING_STEPS = 1_000
    }
}
