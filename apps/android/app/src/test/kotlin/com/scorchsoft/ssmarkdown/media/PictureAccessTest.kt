package com.scorchsoft.ssmarkdown.media

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.scorchsoft.ssmarkdown.media.FakeDocumentTree.Companion.ROOT
import com.scorchsoft.ssmarkdown.media.FakeDocumentTree.Companion.TREE
import com.scorchsoft.ssmarkdown.media.FakeDocumentTree.Companion.doc
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Insert image's storage half (docs/ux-spec.md, "Pictures"): relative paths
 * from the document, Markdown that reads back as the same path, copies under
 * a free name, drafts, and folder grants that must hold the document.
 */
@RunWith(RobolectricTestRunner::class)
class PictureAccessTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var tree: FakeDocumentTree
    private lateinit var docId: String
    private lateinit var notes: String
    private val granted = ArrayList<String>()
    private val released = ArrayList<String>()
    private val links = MemoryLinks()
    private val described = HashMap<String, PickedFile>()

    @Before
    fun setUp() {
        tree = FakeDocumentTree(tmp.newFolder("provider"))
        notes = tree.folder(ROOT, "notes")
        docId = tree.file(notes, "doc.md")
    }

    private fun access(held: List<String> = listOf(FakeDocumentTree.TREE)): SafPictureAccess {
        val listings = TreeListings(tree)
        return SafPictureAccess(
            ApplicationProvider.getApplicationContext<Context>().contentResolver,
            PictureFolders(listings, links, { null }, { held }),
            listings,
            draftFile = {
                if (it.startsWith(
                        "draft:",
                    )
                ) {
                    File(tmp.root, "drafts/${it.substringAfter(':')}.md")
                } else {
                    null
                }
            },
            takeGrant = {
                granted += it
                true
            },
            releaseGrant = { released += it },
            describe = { described[it] },
        )
    }

    /** A picture outside any tree, as the picker hands it over: a readable URI and its description. */
    private fun outsidePicture(
        name: String,
        bytes: ByteArray = byteArrayOf(1, 2, 3),
        mime: String = "image/png",
        size: Long? = bytes.size.toLong(),
    ): String {
        val file = File(tmp.newFolder(), name).apply { writeBytes(bytes) }
        val uri = "file://${file.absolutePath}"
        described[uri] = PickedFile(name, mime, size)
        return uri
    }

    @Test
    fun `relative paths go up and down from the document's folder`() {
        val names = mapOf("F" to "notes", "I" to "img", "A" to "a.png", "S" to "shared", "L" to "logo.svg")
        val nameOf = { _: String, id: String -> names[id] }
        assertEquals(
            listOf("img", "a.png"),
            PictureMarkup.between(listOf("R", "F", "D"), listOf("R", "F", "I", "A"), nameOf),
        )
        assertEquals(
            listOf("..", "shared", "logo.svg"),
            PictureMarkup.between(listOf("R", "F", "D"), listOf("R", "S", "L"), nameOf),
        )
        assertEquals(listOf("a.png"), PictureMarkup.between(listOf("R", "F", "D"), listOf("R", "F", "A"), nameOf))
        assertEquals(
            listOf("..", "..", "a.png"),
            PictureMarkup.between(listOf("R", "S", "F", "D"), listOf("R", "A"), nameOf),
        )
        assertNull(PictureMarkup.between(listOf("R", "F", "D"), listOf("Q", "A"), nameOf))
    }

    @Test
    fun `paths are written so they read back as the same file`() {
        assertEquals("img/my%20pic.png", PictureMarkup.path(listOf("img", "my pic.png")))
        assertEquals("../a%28b%29.png", PictureMarkup.path(listOf("..", "a(b).png")))
        assertEquals("50%25.png", PictureMarkup.path(listOf("50%.png")))
        assertEquals("café/日本.png", PictureMarkup.path(listOf("café", "日本.png")))
        assertEquals("![a\\[1\\]](x.png)", PictureMarkup.image("a[1]", "x.png"))
        assertEquals("my pic", PictureMarkup.altFor("my pic.png"))
        // What we write, the resolver reads back to the same segments.
        assertEquals(
            listOf("img", "my pic.png"),
            PicturePaths.normalise(PictureMarkup.path(listOf("img", "my pic.png"))),
        )
        assertEquals(listOf("50%.png"), PicturePaths.normalise(PictureMarkup.path(listOf("50%.png"))))
        assertEquals(listOf("café", "日本.png"), PicturePaths.normalise(PictureMarkup.path(listOf("café", "日本.png"))))
    }

    @Test
    fun `a copy never takes a name that is in use`() {
        assertEquals("a.png", PictureMarkup.freeName("a.png", setOf("b.png")))
        assertEquals("a-2.png", PictureMarkup.freeName("a.png", setOf("a.png")))
        assertEquals("a-3.png", PictureMarkup.freeName("a.png", setOf("A.PNG", "a-2.png")))
        assertEquals("README-2", PictureMarkup.freeName("README", setOf("readme")))
    }

    @Test
    fun `a picture inside the document's tree is linked where it is`() {
        val img = tree.folder(notes, "img")
        val a = tree.file(img, "a b.png")
        val shared = tree.folder(ROOT, "shared")
        val logo = tree.file(shared, "logo.png")
        val access = access()
        described[doc(a)] = PickedFile("a b.png", "image/png", 3)
        described[doc(logo)] = PickedFile("logo.png", "image/png", 3)
        assertEquals(Placement.Ready("img/a%20b.png", "a b"), access.place(doc(docId), doc(a)))
        assertEquals(Placement.Ready("../shared/logo.png", "logo"), access.place(doc(docId), doc(logo)))
    }

    @Test
    fun `a picture outside the tree, or with no tree, is offered a copy`() {
        val picked = outsidePicture("cat.png")
        assertEquals(Placement.Outside, access().place(doc(docId), picked))
        assertEquals(Placement.Outside, access(held = emptyList()).place(doc(docId), picked))
        assertEquals(Placement.NotImage, access().place(doc(docId), outsidePicture("x.txt", mime = "text/plain")))
    }

    @Test
    fun `copying goes into images beside the document under a free name`() {
        val access = access()
        val first = access.copyIn(doc(docId), outsidePicture("cat.png", byteArrayOf(7)))
        assertEquals(Placement.Ready("images/cat.png", "cat"), first)
        val second = access.copyIn(doc(docId), outsidePicture("cat.png", byteArrayOf(8)))
        assertEquals(Placement.Ready("images/cat-2.png", "cat-2"), second)
        val images = tree.names(notes)
        assertEquals(1, images.count { it == "images" })
        val folder = tree.list(TREE, notes)!!.first { it.name == "images" }.id
        assertEquals(listOf("cat.png", "cat-2.png"), tree.names(folder))
        val copy = tree.list(TREE, folder)!!.first { it.name == "cat-2.png" }.id
        assertArrayEquals(byteArrayOf(8), tree.bytes(copy))
    }

    @Test
    fun `copying needs the folder, and too large a picture is refused without a trace`() {
        assertEquals(Placement.NeedsFolder, access(held = emptyList()).copyIn(doc(docId), outsidePicture("a.png")))
        val big = outsidePicture("big.png", size = DocumentPictures.MAX_PICTURE_BYTES + 1L)
        assertEquals(Placement.TooLarge, access().copyIn(doc(docId), big))
        // A provider that understated the size: the copy stops at the cap and is deleted.
        val liar = outsidePicture("liar.png", ByteArray(DocumentPictures.MAX_PICTURE_BYTES + 10), size = 10)
        assertEquals(Placement.TooLarge, access().copyIn(doc(docId), liar))
        val folder = tree.list(TREE, notes)!!.firstOrNull { it.name == "images" }?.id
        assertEquals(emptyList<String>(), folder?.let(tree::names).orEmpty())
    }

    @Test
    fun `a draft copies into the drafts folder's own images`() {
        File(tmp.root, "drafts").mkdirs()
        val access = access()
        assertEquals(Placement.Outside, access.place("draft:d1", outsidePicture("cat.png")))
        assertEquals(Placement.Ready("images/cat.png", "cat"), access.copyIn("draft:d1", outsidePicture("cat.png")))
        assertEquals(Placement.Ready("images/cat-2.png", "cat-2"), access.copyIn("draft:d1", outsidePicture("cat.png")))
        assertEquals(listOf("cat-2.png", "cat.png"), File(tmp.root, "drafts/images").list()!!.sorted())
    }

    @Test
    fun `a granted folder is kept only if it holds the document`() {
        val access = access(held = emptyList())
        assertEquals(false, access.grantFolder(doc(docId), FakeDocumentTree.OTHER_TREE))
        assertEquals(listOf(FakeDocumentTree.OTHER_TREE), released)
        assertEquals(true, access.grantFolder(doc(docId), TREE))
        assertEquals(TREE, links[doc(docId)])
        assertEquals(listOf(FakeDocumentTree.OTHER_TREE, TREE), granted)
        assertEquals(listOf(FakeDocumentTree.OTHER_TREE), released)
    }
}
