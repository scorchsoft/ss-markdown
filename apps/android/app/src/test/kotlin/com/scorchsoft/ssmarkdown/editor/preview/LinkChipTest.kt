package com.scorchsoft.ssmarkdown.editor.preview

import com.scorchsoft.ssmarkdown.editor.EditorAction
import com.scorchsoft.ssmarkdown.editor.EditorEvent
import com.scorchsoft.ssmarkdown.editor.EditorOverlay
import com.scorchsoft.ssmarkdown.editor.MessageKind
import com.scorchsoft.ssmarkdown.fixtures.EditorFixture
import com.scorchsoft.ssmarkdown.preview.bridge.LinkTarget
import com.scorchsoft.ssmarkdown.settings.AppSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * SEC-7: an external link tapped in Preview always asks first, with the
 * link chip (Open · Copy link), whether "Tap to edit" is on or off. A
 * link never launches another app from a single tap on rendered text.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LinkChipTest {
    @get:Rule val temp = TemporaryFolder()

    private val link = LinkTarget.External("https://example.com/a")

    private fun fixture(tapToEdit: Boolean): EditorFixture {
        val f = EditorFixture(temp.root, UnconfinedTestDispatcher(), AppSettings(tapToEditPreview = tapToEdit))
        f.vm.openDocument(f.file("l.md", "[a](https://example.com/a)\n"))
        return f
    }

    @Test
    fun `with Tap to edit off an external link shows the chip, without Edit link, and opens nothing`() {
        val f = fixture(tapToEdit = false)

        f.vm.preview.onLinkTapped(link, 0)

        assertEquals(EditorOverlay.LinkChip(link, 0, canEdit = false), f.vm.uiState.value.overlay)
        assertTrue(f.events.none { it is EditorEvent.OpenLink })
    }

    @Test
    fun `with Tap to edit on the chip also offers Edit link`() {
        val f = fixture(tapToEdit = true)

        f.vm.preview.onLinkTapped(link, 0)

        assertEquals(EditorOverlay.LinkChip(link, 0, canEdit = true), f.vm.uiState.value.overlay)
    }

    @Test
    fun `the chip's Open opens the link, once, and closes the chip`() {
        val f = fixture(tapToEdit = false)
        f.vm.preview.onLinkTapped(link, 0)

        f.vm.onAction(EditorAction.OpenLink)

        assertEquals(listOf(EditorEvent.OpenLink(link.url)), f.events.filterIsInstance<EditorEvent.OpenLink>())
        assertNull(f.vm.uiState.value.overlay)
    }

    @Test
    fun `the chip's Copy link copies the address and opens nothing`() {
        val f = fixture(tapToEdit = false)
        f.vm.preview.onLinkTapped(link, 0)

        f.vm.onAction(EditorAction.CopyLink)

        assertEquals(EditorEvent.CopyText(link.url), f.events.last())
        assertTrue(f.events.none { it is EditorEvent.OpenLink })
        assertEquals(
            MessageKind.LinkCopied,
            f.vm.uiState.value.message
                ?.kind,
        )
        assertNull(f.vm.uiState.value.overlay)
    }
}
