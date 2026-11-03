package com.scorchsoft.ssmarkdown.editor.preview

import com.scorchsoft.ssmarkdown.editor.EditorAction
import com.scorchsoft.ssmarkdown.editor.Pane
import com.scorchsoft.ssmarkdown.editor.ViewMode
import com.scorchsoft.ssmarkdown.fixtures.EditorFixture
import com.scorchsoft.ssmarkdown.preview.bridge.HostMessage
import com.scorchsoft.ssmarkdown.preview.bridge.PageMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The Preview pane's fast-scroll thumb (docs/ux-spec.md, "Fast scroll")
 * over a recording page: what shows it, the scroll a drag sends (one a
 * frame), Split moving only the dragged pane, and the section bubble.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PreviewFastScrollTest {
    @get:Rule val temp = TemporaryFolder()

    private val page = ArrayList<HostMessage>()
    private val frames = ArrayList<Runnable>()

    private fun fixture(): EditorFixture {
        val f = EditorFixture(temp.root, UnconfinedTestDispatcher())
        val text =
            (0 until LINES).joinToString("") {
                if (it % SECTION ==
                    0
                ) {
                    "# Part ${it / SECTION}\n"
                } else {
                    "line $it\n"
                }
            }
        f.vm.openDocument(f.file("long.md", text))
        f.vm.preview.attachPage { page += it }
        f.vm.preview.fastScroll.frames = FrameScheduler { frames += it }
        page.clear()
        return f
    }

    /** The display's next frame. */
    private fun frame() {
        val due = frames.toList()
        frames.clear()
        due.forEach(Runnable::run)
    }

    private fun scrollsToFraction() = page.filterIsInstance<HostMessage.ScrollToFraction>().map { it.fraction }

    private fun readerScrolls(
        f: EditorFixture,
        fraction: Float,
        visible: Float,
    ) {
        val thumb = f.vm.preview.fastScroll.state
        thumb.onTouch(true)
        PageMessage.TopLine(0, emptyList(), fraction, visible).deliverTo(f.vm.preview)
        thumb.onTouch(false)
    }

    @Test
    fun `a long page shows the thumb when the reader scrolls it, a short one never does`() {
        val f = fixture()
        val thumb = f.vm.preview.fastScroll.state
        readerScrolls(f, 0.2f, visible = 0.5f)
        assertFalse("two screens: no thumb", thumb.isShown)
        readerScrolls(f, 0.3f, visible = 0.1f)
        assertTrue("ten screens", thumb.isShown)
        assertEquals(0.3f, thumb.fraction, 0f)
    }

    @Test
    fun `a drag sends the page the fraction to scroll to, the latest one, once a frame`() {
        val f = fixture()
        val fast = f.vm.preview.fastScroll
        fast.dragTo(0.25f)
        fast.dragTo(0.4f)
        fast.dragTo(0.5f)
        assertTrue("nothing before the frame", scrollsToFraction().isEmpty())
        frame()
        assertEquals(listOf(0.5f), scrollsToFraction())
        frame()
        assertEquals("nothing new, nothing sent", listOf(0.5f), scrollsToFraction())

        fast.dragTo(0.75f)
        fast.end()
        frame()
        assertEquals("the last position still lands, once", listOf(0.5f, 0.75f), scrollsToFraction())
        assertEquals(2, fast.sent)
    }

    @Test
    fun `in Split, dragging the preview's thumb moves the page only, and the Code pane follows the page's report`() {
        val f = fixture()
        f.vm.onAction(EditorAction.SelectMode(ViewMode.Split))
        // The thumb is inside the Preview pane: the finger that takes it makes Preview lead.
        f.vm.onAction(EditorAction.PaneTouched(Pane.Preview))
        page.clear()

        f.vm.preview.fastScroll
            .dragTo(0.5f)
        frame()
        assertEquals(listOf(0.5f), scrollsToFraction())
        assertTrue("the thumb never scrolls the page by line", page.none { it is HostMessage.ScrollToLine })
        assertEquals("nothing moved the anchor yet", 0, f.vm.anchorLine.value)

        // The page scrolled and says so: the ordinary sync moves the anchor (and Code with it).
        PageMessage.TopLine(SECTION * 2, emptyList(), 0.5f, 0.1f).deliverTo(f.vm.preview)
        assertEquals(SECTION * 2, f.vm.anchorLine.value)
        assertTrue("and never echoes back to the page", page.none { it is HostMessage.ScrollToLine })
    }

    @Test
    fun `in Split, the Code pane leading never sends the page a fraction`() {
        val f = fixture()
        f.vm.onAction(EditorAction.SelectMode(ViewMode.Split))
        page.clear()
        f.vm.onPaneTopLine(Pane.Code, 40)
        assertEquals(
            "the page follows by line, as for any Code scroll",
            1,
            page.count { it is HostMessage.ScrollToLine },
        )
        assertTrue(scrollsToFraction().isEmpty())
    }

    @Test
    fun `the bubble names the heading above the page's top line, and nothing before the first`() {
        val f = fixture()
        val fast = f.vm.preview.fastScroll
        PageMessage.TopLine(SECTION * 3 + 5, emptyList(), 0.3f, 0.1f).deliverTo(f.vm.preview)
        fast.begin()
        assertEquals("Part 3", fast.state.section)
        assertEquals(3, fast.state.sectionKey)

        val g = EditorFixture(temp.newFolder(), UnconfinedTestDispatcher())
        g.vm.openDocument(g.file("plain.md", (0 until LINES).joinToString("") { "line $it\n" }))
        g.vm.preview.fastScroll
            .begin()
        assertEquals("no headings: no bubble in Preview", null, g.vm.preview.fastScroll.state.section)
    }

    @Test
    fun `a selection on the page keeps the thumb away until editing ends`() {
        val f = fixture()
        val thumb = f.vm.preview.fastScroll.state
        readerScrolls(f, 0.3f, visible = 0.1f)
        assertTrue(thumb.isShown)
        f.vm.preview.onSelection(4, 9, emptySet(), false, f.vm.preview.rev)
        assertFalse("handles and the toolbar own the edge", thumb.isShown)
        readerScrolls(f, 0.4f, visible = 0.1f)
        assertFalse(thumb.isShown)
        f.vm.preview.onSelection(9, 9, emptySet(), false, f.vm.preview.rev)
        readerScrolls(f, 0.5f, visible = 0.1f)
        assertTrue("a caret is not a selection", thumb.isShown)
        f.vm.preview.onSelection(4, 9, emptySet(), false, f.vm.preview.rev)
        f.vm.preview.onEditingChanged(false)
        readerScrolls(f, 0.6f, visible = 0.1f)
        assertTrue(thumb.isShown)
    }

    private companion object {
        const val LINES = 400
        const val SECTION = 40
    }
}
