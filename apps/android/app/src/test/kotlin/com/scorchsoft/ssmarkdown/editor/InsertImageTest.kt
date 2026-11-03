package com.scorchsoft.ssmarkdown.editor

import com.scorchsoft.ssmarkdown.fixtures.EditorFixture
import com.scorchsoft.ssmarkdown.fixtures.QueuedDispatcher
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
        ): Boolean = grantAnswer.also { calls += "grant $tree" }.also { grantedFor += document }

        /** The document each grant was asked for. */
        val grantedFor = ArrayList<String>()
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

    // Insert image across a file switch (edge-cases): every step belongs to the document it began in.

    private val io = QueuedDispatcher()

    /** p.md on screen and q.md beside it, with disk work queued for the test to run. */
    private fun switching(): EditorFixture {
        val f = EditorFixture(temp.root, UnconfinedTestDispatcher(), io = io, pictureAccess = access)
        f.file("q.md", "other\n")
        f.vm.openDocument(f.file("p.md", "x\n"))
        io.drain()
        return f
    }

    private fun EditorFixture.show(name: String) {
        vm.openDocument(EditorFixture.SCHEME + name)
        io.drain()
        assertEquals(
            EditorFixture.SCHEME + name,
            vm.active
                ?.open
                ?.uri
                ?.value,
        )
    }

    private fun EditorFixture.messageKind() =
        vm.uiState.value.message
            ?.kind

    /** p.md showing "Copy into this file's folder" for a picture outside it. */
    private fun EditorFixture.offeredACopy() {
        vm.onAction(EditorAction.StripKeyTapped(StripKey.Image))
        access.placed = Placement.Outside
        vm.pictures.onImagePicked("content://media/1")
        io.drain()
        assertEquals(EditorOverlay.CopyPicture("content://media/1"), vm.uiState.value.overlay)
    }

    @Test
    fun `a copy that finishes after another file opened links nothing and says where the picture went`() {
        val f = switching()
        f.offeredACopy()
        access.copies += Placement.Ready("images/1.png", "1")
        f.vm.onAction(EditorAction.CopyPictureIn)
        val copy = io.take() // Still writing the picture...
        f.show("q.md") // ...when the user opens another file.
        copy.run()
        io.drain()
        assertEquals("other\n", f.text())
        assertEquals("x\n", f.disk("p.md"))
        assertEquals(MessageKind.PictureCopiedNotLinked, f.messageKind())
        assertEquals(
            "images/1.png",
            f.vm.uiState.value.message
                ?.argument,
        )
    }

    @Test
    fun `a copy that finishes after leaving and coming back links nothing either`() {
        val f = switching()
        f.offeredACopy()
        access.copies += Placement.Ready("images/1.png", "1")
        f.vm.onAction(EditorAction.CopyPictureIn)
        val copy = io.take()
        f.show("q.md")
        f.show("p.md")
        copy.run()
        io.drain()
        assertEquals("x\n", f.text())
        assertEquals(MessageKind.PictureCopiedNotLinked, f.messageKind())
    }

    @Test
    fun `a picture looked up while another file opened never offers a copy on that file`() {
        val f = switching()
        f.vm.onAction(EditorAction.StripKeyTapped(StripKey.Image))
        access.placed = Placement.Outside
        f.vm.pictures.onImagePicked("content://media/1")
        val lookup = io.take()
        f.show("q.md")
        lookup.run()
        io.drain()
        assertNull(f.vm.uiState.value.overlay)
        assertEquals(MessageKind.PictureInsertStopped, f.messageKind())
        assertEquals("other\n", f.text())
    }

    @Test
    fun `the copy prompt goes away, with a word, when another file opens`() {
        val f = switching()
        f.offeredACopy()
        f.show("q.md")
        assertNull("no prompt that would do nothing on q.md", f.vm.uiState.value.overlay)
        assertEquals(MessageKind.PictureInsertStopped, f.messageKind())
        assertFalse(access.calls.any { it.startsWith("copy") })
    }

    @Test
    fun `a folder granted after another file opened is for the first file, and nothing is copied`() {
        val f = switching()
        f.offeredACopy()
        access.copies += Placement.NeedsFolder
        f.vm.onAction(EditorAction.CopyPictureIn)
        io.drain()
        assertEquals(EditorEvent.PickPictureFolder("start:test:p.md"), f.events.last())
        f.show("q.md")
        f.vm.pictures.onFolderPicked("content://tree/notes")
        io.drain()
        assertEquals(listOf("test:p.md"), access.grantedFor)
        assertEquals(1, access.calls.count { it.startsWith("copy") })
        assertEquals("other\n", f.text())
        assertEquals(MessageKind.PictureInsertStopped, f.messageKind())
    }
}
