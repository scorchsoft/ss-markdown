package com.scorchsoft.ssmarkdown.find

import com.scorchsoft.ssmarkdown.editor.EditorAction
import com.scorchsoft.ssmarkdown.editor.Pane
import com.scorchsoft.ssmarkdown.editor.ViewMode
import com.scorchsoft.ssmarkdown.fixtures.EditorFixture
import com.scorchsoft.ssmarkdown.markdown.edit.TextSelection
import com.scorchsoft.ssmarkdown.preview.bridge.HostMessage
import com.scorchsoft.ssmarkdown.text.EditOp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Next and Previous in the find bar (edge-cases "Find next does not move"):
 * the state machine (start at the cursor, wrap with a sign, follow edits,
 * a new query starts again) and what reaches each pane. In Code the match
 * is selected; in Preview and Split the page is told to paint the
 * matches, mark the current one and scroll to it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FindNavigationTest {
    @get:Rule val temp = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()

    private fun EditorFixture.find(action: FindAction) = vm.onAction(EditorAction.Find(action))

    private fun EditorFixture.state(): FindUiState = vm.uiState.value.find ?: error("find bar closed")

    private fun EditorFixture.selection(): TextSelection = vm.active?.surface?.selection ?: error("nothing open")

    private fun EditorFixture.open(text: String): EditorFixture {
        vm.openDocument(file("f.md", text))
        return this
    }

    private fun EditorFixture.pageMessages(): MutableList<HostMessage> {
        val page = mutableListOf<HostMessage>()
        vm.preview.attachPage { page += it }
        return page
    }

    private val text = "a cat, a cat, a cat and a dog\n"
    private val cats = listOf(2, 9, 16)

    @Test
    fun `the first match is the one at or after the cursor, and next and previous wrap round with a sign`() {
        val f = EditorFixture(temp.root, dispatcher).open(text)
        f.vm.active
            ?.surface
            ?.select(5, 5)
        f.find(FindAction.Open)
        f.find(FindAction.Query("cat"))
        assertEquals("starts after the cursor", 1, f.state().current)
        assertEquals(TextSelection(9, 12), f.selection())
        assertNull(f.state().wrapped)

        f.find(FindAction.Next)
        assertEquals(2, f.state().current)
        assertNull(f.state().wrapped)
        f.find(FindAction.Next)
        assertEquals(0, f.state().current)
        assertEquals(FindWrap.ToFirst, f.state().wrapped)
        assertEquals(TextSelection(2, 5), f.selection())

        f.find(FindAction.Previous)
        assertEquals(2, f.state().current)
        assertEquals(FindWrap.ToLast, f.state().wrapped)
        assertEquals(TextSelection(16, 19), f.selection())
        f.find(FindAction.Previous)
        assertNull("the sign goes with the next ordinary step", f.state().wrapped)
    }

    @Test
    fun `a new query starts again from the cursor`() {
        val f = EditorFixture(temp.root, dispatcher).open(text)
        f.find(FindAction.Open)
        f.find(FindAction.Query("cat"))
        f.find(FindAction.Next)
        f.find(FindAction.Next)
        f.find(FindAction.Next)
        assertEquals(FindWrap.ToFirst, f.state().wrapped)
        f.find(FindAction.Query("dog"))
        assertEquals(0, f.state().current)
        assertNull(f.state().wrapped)
        assertEquals(TextSelection(26, 29), f.selection())
    }

    @Test
    fun `an edit that shifts the matches keeps next on the following match`() {
        val f = EditorFixture(temp.root, dispatcher).open(text)
        f.find(FindAction.Open)
        f.find(FindAction.Query("cat"))
        f.find(FindAction.Next)
        assertEquals(TextSelection(9, 12), f.selection())
        // Text typed before every match moves them all by three.
        val doc = f.vm.active ?: error("nothing open")
        doc.surface.apply(listOf(EditOp(0, "", "Oh ")), TextSelection(12, 15))
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(cats.map { it + 3 }, f.state().matches.map { it.start })
        assertEquals("still on the second cat", 1, f.state().current)
        f.find(FindAction.Next)
        assertEquals(TextSelection(19, 22), f.selection())
    }

    @Test
    fun `replace selects the next match, and replace all still works after navigating`() {
        val f = EditorFixture(temp.root, dispatcher).open(text)
        f.find(FindAction.Open)
        f.find(FindAction.Query("cat"))
        f.find(FindAction.Next)
        f.find(FindAction.Replacement("dog"))
        f.find(FindAction.ReplaceOne)
        assertEquals(
            "a cat, a dog, a cat and a dog\n",
            f.vm.active
                ?.text
                ?.text
                .toString(),
        )
        assertEquals("the next cat is current", TextSelection(16, 19), f.selection())
        f.find(FindAction.ReplaceAll)
        assertEquals(
            "a dog, a dog, a dog and a dog\n",
            f.vm.active
                ?.text
                ?.text
                .toString(),
        )
    }

    @Test
    fun `in Preview the page is sent the matches, then each step as the current index to reveal`() {
        val f = EditorFixture(temp.root, dispatcher).open(text)
        f.vm.onAction(EditorAction.SelectMode(ViewMode.Preview))
        val page = f.pageMessages()
        f.find(FindAction.Open)
        f.find(FindAction.Query("cat"))
        val first = page.filterIsInstance<HostMessage.Find>().last()
        assertArrayEquals(cats.toIntArray(), first.starts)
        assertArrayEquals(cats.map { it + 3 }.toIntArray(), first.ends)
        assertEquals(0, first.current)
        assertTrue(first.reveal)

        f.find(FindAction.Next)
        f.find(FindAction.Next)
        val step = page.filterIsInstance<HostMessage.Find>().last()
        assertNull("a step resends no ranges", step.starts)
        assertEquals(2, step.current)
        assertTrue(step.reveal)
        assertEquals("the source selection follows too", TextSelection(16, 19), f.selection())

        f.find(FindAction.Close)
        val cleared = page.filterIsInstance<HostMessage.Find>().last()
        assertEquals(0, cleared.starts?.size)
        assertEquals(-1, cleared.current)
    }

    @Test
    fun `in Code the page is not sent matches, and switching to Preview sends them`() {
        val f = EditorFixture(temp.root, dispatcher).open(text)
        val page = f.pageMessages()
        f.find(FindAction.Open)
        f.find(FindAction.Query("cat"))
        f.find(FindAction.Next)
        assertTrue(page.none { it is HostMessage.Find })

        f.vm.onAction(EditorAction.SelectMode(ViewMode.Split))
        val sent = page.filterIsInstance<HostMessage.Find>().single()
        assertArrayEquals(cats.toIntArray(), sent.starts)
        assertEquals(1, sent.current)
    }

    @Test
    fun `in Split a step selects in Code, reveals in Preview, and makes Code the pane the other follows`() {
        val f = EditorFixture(temp.root, dispatcher).open(text)
        f.vm.onAction(EditorAction.SelectMode(ViewMode.Split))
        f.vm.onAction(EditorAction.PaneTouched(Pane.Preview))
        val page = f.pageMessages()
        f.find(FindAction.Open)
        f.find(FindAction.Query("cat"))
        f.find(FindAction.Next)
        assertEquals(TextSelection(9, 12), f.selection())
        val step = page.filterIsInstance<HostMessage.Find>().last()
        assertEquals(1, step.current)
        assertTrue(step.reveal)
        assertEquals(Pane.Code, f.vm.scrollLink.leader(ViewMode.Split))
    }
}
