package com.scorchsoft.ssmarkdown.editor

import com.scorchsoft.ssmarkdown.fixtures.EditorFixture
import com.scorchsoft.ssmarkdown.navigator.NavigatorAction
import com.scorchsoft.ssmarkdown.preview.FormattedCopy
import com.scorchsoft.ssmarkdown.preview.PrintOutcome
import com.scorchsoft.ssmarkdown.preview.PrintTarget
import com.scorchsoft.ssmarkdown.settings.CopyFormat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Copy's choice (ux-spec "Top bar"): Copy opens the chooser with the last
 * choice first, a choice copies and is remembered, long-press repeats it,
 * and formatted text comes from a drawn page holding this document (from
 * Code the Preview comes into view and Code returns), with an honest
 * message for every outcome.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CopyFormattedTest {
    @get:Rule val temp = TemporaryFolder()

    private val scheduler = TestCoroutineScheduler()
    private val f by lazy { EditorFixture(temp.root, UnconfinedTestDispatcher(scheduler)) }

    /** The Preview page as the session sees it: drawn or not, and the copies it was asked for. */
    private inner class FakePage : PrintTarget {
        override var isOnScreen = true
        val asked = ArrayList<Triple<Int, Int, Int>>()
        var answer: CompletableDeferred<FormattedCopy?>? = null
        var copy: FormattedCopy? = FormattedCopy("<h1 style=\"x\">Title</h1>", "Title")

        override suspend fun print(jobName: String) = PrintOutcome.Opened

        override suspend fun exportFormatted(
            fromLine: Int,
            toLine: Int,
            maxChars: Int,
        ): FormattedCopy? {
            asked += Triple(fromLine, toLine, maxChars)
            return answer?.await() ?: copy
        }
    }

    private val page = FakePage()

    private fun open(
        name: String = "a.md",
        text: String = DOC,
    ) = f.vm.openDocument(f.file(name, text))

    private fun attach() = f.vm.preview.attachPage(page) {}

    private fun message() =
        f.vm.uiState.value.message
            ?.kind

    private fun state() = f.vm.uiState.value

    /** The text of the last plain copy. */
    private fun copied() = (f.events.last() as EditorEvent.CopyText).text.toString()

    @Test
    fun `Copy opens the chooser with the last choice first, and a choice is remembered`() {
        open()
        f.vm.onAction(EditorAction.CopyTapped)
        assertEquals(EditorOverlay.CopyChooser(CopyFormat.Markdown), state().overlay)

        f.vm.onAction(EditorAction.CopyAs(CopyFormat.Markdown))
        assertNull(state().overlay)
        assertEquals(DOC, copied())
        assertEquals(MessageKind.Copied, message())

        f.vm.onAction(EditorAction.SelectMode(ViewMode.Preview))
        attach()
        f.vm.onAction(EditorAction.CopyAs(CopyFormat.Formatted))
        assertEquals(CopyFormat.Formatted, f.settings.settings.value.copyFormat)
        assertEquals(CopyFormat.Formatted, state().copyFormat)
        f.vm.onAction(EditorAction.CopyTapped)
        assertEquals(
            "the last choice is listed first",
            EditorOverlay.CopyChooser(CopyFormat.Formatted),
            state().overlay,
        )
    }

    @Test
    fun `long-press Copy repeats the last choice at once`() {
        open()
        f.vm.onAction(EditorAction.SelectMode(ViewMode.Preview))
        attach()
        f.settings.update { it.copy(copyFormat = CopyFormat.Formatted) }

        f.vm.onAction(EditorAction.CopyLongPressed)

        assertNull("no chooser on long-press", state().overlay)
        assertEquals(EditorEvent.CopyFormatted(page.copy!!), f.events.last())
        assertEquals(MessageKind.CopiedFormatted, message())
    }

    @Test
    fun `formatted text asks the page for every line within the clipboard budget`() {
        open()
        f.vm.onAction(EditorAction.SelectMode(ViewMode.Split))
        attach()

        f.vm.onAction(EditorAction.CopyAs(CopyFormat.Formatted))

        assertEquals(listOf(Triple(0, DOC.count { it == '\n' } + 1, 400_000)), page.asked)
        assertEquals(ViewMode.Split, state().mode)
        assertEquals(MessageKind.CopiedFormatted, message())
    }

    @Test
    fun `from Code the Preview comes into view to render, then Code returns`() {
        open()
        attach()
        f.vm.onAction(EditorAction.SelectMode(ViewMode.Code))
        page.answer = CompletableDeferred()

        f.vm.onAction(EditorAction.CopyAs(CopyFormat.Formatted))
        assertEquals(ViewMode.Preview, state().mode)
        page.answer?.complete(page.copy)

        assertEquals(ViewMode.Code, state().mode)
        assertTrue(f.events.last() is EditorEvent.CopyFormatted)
    }

    @Test
    fun `a shortened copy says so`() {
        open()
        f.vm.onAction(EditorAction.SelectMode(ViewMode.Preview))
        attach()
        page.copy = FormattedCopy("<p>a</p>", "a", truncated = true)

        f.vm.onAction(EditorAction.CopyAs(CopyFormat.Formatted))

        assertEquals(MessageKind.CopiedFormattedShortened, message())
    }

    @Test
    fun `no page in time copies the Markdown instead and says so`() {
        open()
        page.isOnScreen = false
        attach()
        f.vm.onAction(EditorAction.SelectMode(ViewMode.Preview))

        f.vm.onAction(EditorAction.CopyAs(CopyFormat.Formatted))
        scheduler.advanceTimeBy(5_000)

        assertEquals(DOC, copied())
        assertEquals(MessageKind.FormattedFailed, message())
    }

    @Test
    fun `another file opened while the page renders never lends its text`() {
        open("a.md")
        f.vm.onAction(EditorAction.SelectMode(ViewMode.Preview))
        attach()
        page.answer = CompletableDeferred()
        f.vm.onAction(EditorAction.CopyAs(CopyFormat.Formatted))

        open("b.md", "# B\n")
        page.answer?.complete(FormattedCopy("<h1>B</h1>", "B"))

        assertTrue(f.events.none { it is EditorEvent.CopyFormatted })
        assertEquals("A's own Markdown goes instead", DOC, copied())
        assertEquals(MessageKind.FormattedFailed, message())
    }

    @Test
    fun `the section menu lists the last choice first and copies just the section`() {
        open()
        f.vm.onAction(EditorAction.SelectMode(ViewMode.Preview))
        attach()
        f.settings.update { it.copy(copyFormat = CopyFormat.Formatted) }
        f.vm.onAction(EditorAction.Navigator(NavigatorAction.HeadingMenu(SECTION_LINE)))
        val menu = state().overlay as EditorOverlay.SectionMenu
        assertEquals(CopyFormat.Formatted, menu.copyFirst)

        f.vm.onAction(EditorAction.Navigator(NavigatorAction.CopySection(SECTION_LINE, CopyFormat.Formatted)))
        assertEquals(Triple(SECTION_LINE, SECTION_LINE + 4, 400_000), page.asked.single())
        assertEquals(MessageKind.SectionCopiedFormatted, message())

        f.vm.onAction(EditorAction.Navigator(NavigatorAction.CopySection(SECTION_LINE, CopyFormat.Markdown)))
        assertEquals("## Two\n\nBeta.\n", copied())
        assertEquals(MessageKind.SectionCopied, message())
        assertEquals(CopyFormat.Markdown, f.settings.settings.value.copyFormat)
    }

    private companion object {
        const val DOC = "# One\n\nAlpha.\n\n## Two\n\nBeta.\n"
        const val SECTION_LINE = 4
    }
}
