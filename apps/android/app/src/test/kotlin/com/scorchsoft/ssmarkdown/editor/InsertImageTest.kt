package com.scorchsoft.ssmarkdown.editor

import com.scorchsoft.ssmarkdown.fixtures.EditorFixture
import com.scorchsoft.ssmarkdown.markdown.edit.TextSelection
import com.scorchsoft.ssmarkdown.media.PictureAccess
import com.scorchsoft.ssmarkdown.media.Placement
import com.scorchsoft.ssmarkdown.preview.ImageStatus
import com.scorchsoft.ssmarkdown.preview.bridge.HostMessage
import com.scorchsoft.ssmarkdown.preview.bridge.PreviewFormatRequest
import com.scorchsoft.ssmarkdown.preview.editing.FormatAction
import com.scorchsoft.ssmarkdown.ui.strip.StripKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Insert image and "Allow access" in the editor (docs/ux-spec.md,
 * "Pictures"): the picker starts in the document's folder, a picture in the
 * tree is linked as one undo step with its alt text selected, one outside is
 * offered a copy, and a copy that needs the folder asks for it first.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InsertImageTest {
    @get:Rule val temp = TemporaryFolder()

    /** Storage as a script: what each call answers, and what was asked. */
    private class ScriptedAccess : PictureAccess {
        var placed: Placement = Placement.Outside
        val copies = ArrayDeque<Placement>()
        var grantAnswer = true
        val calls = ArrayList<String>()

        override fun initialFolder(document: String) = "start:$document"

        override fun place(
            document: String,
            picked: String,
        ): Placement = placed.also { calls += "place $picked" }

        override fun copyIn(
            document: String,
            picked: String,
        ): Placement = copies.removeFirst().also { calls += "copy $picked" }

        override fun grantFolder(
            document: String,
            tree: String,
        ): Boolean = grantAnswer.also { calls += "grant $tree" }
    }

    private val access = ScriptedAccess()

    private fun fixture(text: String): EditorFixture {
        val f = EditorFixture(temp.root, UnconfinedTestDispatcher(), pictureAccess = access)
        f.vm.openDocument(f.file("p.md", text))
        return f
    }

    private fun EditorFixture.text() =
        vm.active
            ?.text
            ?.text
            ?.toString()

    private fun EditorFixture.selection() = vm.active?.surface?.selection

    @Test
    fun `the key opens the picker in the document's folder, and a picture in the tree is linked`() {
        val f = fixture("See here\n")
        f.vm.active
            ?.surface
            ?.select(4, 4)
        f.vm.onAction(EditorAction.StripKeyTapped(StripKey.Image))
        assertEquals(EditorEvent.PickImage("start:test:p.md"), f.events.last())
        access.placed = Placement.Ready("img/cat%20one.png", "cat one")
        f.vm.pictures.onImagePicked("content://x/document/cat")
        assertEquals("See ![cat one](img/cat%20one.png)here\n", f.text())
        // The alt text is selected, so typing replaces it.
        assertEquals(TextSelection(6, 13), f.selection())
        // One undo step takes it all away.
        f.vm.onAction(EditorAction.StripKeyTapped(StripKey.Undo))
        assertEquals("See here\n", f.text())
    }

    @Test
    fun `a selection is replaced by the picture`() {
        val f = fixture("old words\n")
        f.vm.active
            ?.surface
            ?.select(0, 3)
        f.vm.onAction(EditorAction.ChooseMenuItem(MenuItem.InsertImage))
        access.placed = Placement.Ready("a.png", "a")
        f.vm.pictures.onImagePicked("content://x/document/a")
        assertEquals("![a](a.png) words\n", f.text())
    }

    @Test
    fun `cancelling the picker changes nothing`() {
        val f = fixture("x\n")
        f.vm.onAction(EditorAction.StripKeyTapped(StripKey.Image))
        f.vm.pictures.onImagePicked(null)
        assertEquals("x\n", f.text())
        assertTrue(access.calls.isEmpty())
    }

    @Test
    fun `a picture outside the folder is offered a copy, and Cancel writes nothing`() {
        val f = fixture("x\n")
        f.vm.onAction(EditorAction.StripKeyTapped(StripKey.Image))
        access.placed = Placement.Outside
        f.vm.pictures.onImagePicked("content://media/1")
        assertEquals(EditorOverlay.CopyPicture("content://media/1"), f.vm.uiState.value.overlay)
        f.vm.onAction(EditorAction.DismissOverlay)
        assertEquals("x\n", f.text())
        assertFalse(access.calls.any { it.startsWith("copy") })
    }

    @Test
    fun `Copy needs the folder first, so it asks, then copies and links the copy`() {
        val f = fixture("x\n")
        f.vm.active
            ?.surface
            ?.select(1, 1)
        f.vm.onAction(EditorAction.StripKeyTapped(StripKey.Image))
        f.vm.pictures.onImagePicked("content://media/1")
        access.copies += Placement.NeedsFolder
        access.copies += Placement.Ready("images/1.png", "1")
        f.vm.onAction(EditorAction.CopyPictureIn)
        assertNull(f.vm.uiState.value.overlay)
        assertEquals(EditorEvent.PickPictureFolder("start:test:p.md"), f.events.last())
        f.vm.pictures.onFolderPicked("content://tree/notes")
        assertEquals(
            listOf(
                "place content://media/1",
                "copy content://media/1",
                "grant content://tree/notes",
                "copy content://media/1",
            ),
            access.calls,
        )
        assertEquals("x![1](images/1.png)\n", f.text())
    }

    @Test
    fun `an unrelated folder is explained and not used`() {
        val f = fixture("x\n")
        access.grantAnswer = false
        f.vm.pictures.askForFolder()
        f.vm.pictures.onFolderPicked("content://tree/elsewhere")
        assertEquals(
            MessageKind.PictureFolderUnrelated,
            f.vm.uiState.value.message
                ?.kind,
        )
    }

    @Test
    fun `a folder the page needs shows in the menu until it is granted`() {
        val f = fixture("![a](a.png)\n")
        f.vm.preview.onImageStatuses(mapOf("0/a.png" to ImageStatus.NO_ACCESS))
        assertTrue(f.vm.uiState.value.preview.picturesNeedFolder)
        f.vm.onAction(EditorAction.ChooseMenuItem(MenuItem.ShowPictures))
        assertEquals(EditorEvent.PickPictureFolder("start:test:p.md"), f.events.last())
        f.vm.pictures.onFolderPicked("content://tree/notes")
        assertFalse(f.vm.uiState.value.preview.picturesNeedFolder)
        assertEquals(
            MessageKind.PictureFolderGranted,
            f.vm.uiState.value.message
                ?.kind,
        )
    }

    @Test
    fun `in Preview the picture goes at the page's selection`() {
        val f = fixture("Read the docs today\n")
        val page = ArrayList<HostMessage>()
        f.vm.preview.attachPage { page += it }
        val rev = page.filterIsInstance<HostMessage.Load>().last().rev
        f.vm.preview.onFormatRequest(PreviewFormatRequest(FormatAction.IMAGE, 9, 13, rev, false))
        assertTrue(f.events.last() is EditorEvent.PickImage)
        access.placed = Placement.Ready("d.png", "d")
        f.vm.pictures.onImagePicked("content://x/document/d")
        assertEquals("Read the ![d](d.png) today\n", f.text())
        assertTrue(page.last() is HostMessage.Edit || page.any { it is HostMessage.Edit })
    }
}
