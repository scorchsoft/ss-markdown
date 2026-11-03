package com.scorchsoft.ssmarkdown.media

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.scorchsoft.ssmarkdown.media.FakeDocumentTree.Companion.ROOT
import com.scorchsoft.ssmarkdown.media.FakeDocumentTree.Companion.TREE
import com.scorchsoft.ssmarkdown.media.FakeDocumentTree.Companion.doc
import com.scorchsoft.ssmarkdown.platform.BoundedRead
import com.scorchsoft.ssmarkdown.preview.ImageStatus
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Pictures beside a document: found through the folder grant for every
 * provider, never outside the granted tree (SEC-9), never read whole when
 * hostile-sized (SEC-8), each failure with its reason for the placeholder,
 * and a page of pictures for about one listing (docs/ux-spec.md, "Pictures").
 */
@RunWith(RobolectricTestRunner::class)
class DocumentPicturesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var tree: FakeDocumentTree
    private lateinit var notes: String
    private lateinit var img: String
    private lateinit var docId: String
    private var now = 0L

    /** R/notes/doc.md, R/notes/img/a.png, R/x.png, R/notes/readme.txt, R/notes/huge.png. */
    @Before
    fun tree() {
        tree = FakeDocumentTree(tmp.newFolder("provider"))
        notes = tree.folder(ROOT, "notes")
        docId = tree.file(notes, "doc.md")
        img = tree.folder(notes, "img")
        tree.file(img, "a.png", "A".toByteArray())
        tree.file(img, "my pic.png", "S".toByteArray())
        tree.file(ROOT, "x.png", "X".toByteArray())
        tree.file(notes, "readme.txt")
        tree.file(notes, "huge.png", ByteArray(16))
    }

    private fun pictures(
        trees: List<String> = listOf(TREE),
        document: String? = doc(docId),
        maxBytes: Long? = null,
    ): DocumentPictures {
        val listings = TreeListings(sizes(maxBytes), nanoTime = { now })
        val folders = PictureFolders(listings, MemoryLinks(), folderTree = { null }, heldTrees = { trees })
        return DocumentPictures(
            ApplicationProvider.getApplicationContext<Context>().contentResolver,
            documentUri = { document },
            folders = folders,
            listings = listings,
        )
    }

    /** The tree, with every file over [maxBytes] reported as huge (to test the cap without 32 MB files). */
    private fun sizes(maxBytes: Long?): DocumentTree =
        if (maxBytes == null) {
            tree
        } else {
            object : DocumentTree by tree {
                override fun list(
                    tree: String,
                    folder: String,
                ) = this@DocumentPicturesTest.tree.list(tree, folder)?.map {
                    if ((it.size ?: 0) > maxBytes) it.copy(size = DocumentPictures.MAX_PICTURE_BYTES + 1L) else it
                }
            }
        }

    @Test
    fun `pictures resolve through the tree grant on a provider with opaque ids`() {
        val pictures = pictures()
        assertEquals("A", pictures.bytes("img/a.png")?.decodeToString())
        assertEquals("A", pictures.bytes("./img/../img/a.png")?.decodeToString())
        assertEquals("S", pictures.bytes("img/my%20pic.png")?.decodeToString())
        assertEquals("X", pictures.bytes("../x.png")?.decodeToString())
        assertNull(pictures.bytes("../../x.png"))
        assertNull(pictures.bytes("img/missing.png"))
        assertNull(pictures.bytes("https://example.com/a.png"))
    }

    @Test
    fun `every failure says why`() {
        val pictures = pictures(maxBytes = 8)
        assertEquals(ImageStatus.OK, pictures.status("img/a.png"))
        assertEquals(ImageStatus.NOT_FOUND, pictures.status("img/missing.png"))
        // Climbing out of the granted folder is not found, never a peek outside (SEC-9).
        assertEquals(ImageStatus.NOT_FOUND, pictures.status("../../x.png"))
        assertEquals(ImageStatus.NOT_IMAGE, pictures.status("readme.txt"))
        assertEquals(ImageStatus.NOT_IMAGE, pictures.status("img"))
        assertEquals(ImageStatus.TOO_LARGE, pictures.status("huge.png"))
        assertNull(pictures.open("huge.png"))
        assertEquals(ImageStatus.NOT_FOUND, pictures.status("https://example.com/a.png"))
    }

    @Test
    fun `a file opened on its own, with no folder grant, has no folder access`() {
        val pictures = pictures(trees = emptyList())
        assertEquals(ImageStatus.NO_ACCESS, pictures.status("img/a.png"))
        assertNull(pictures.open("img/a.png"))
    }

    @Test
    fun `a page of fifty pictures in one folder costs one listing of it`() {
        repeat(50) { tree.file(img, "p$it.png") }
        val pictures = pictures()
        tree.listCalls = 0
        tree.pathCalls = 0
        repeat(50) { assertEquals(ImageStatus.OK, pictures.status("img/p$it.png")) }
        repeat(50) { assertTrue(pictures.open("img/p$it.png") != null) }
        // One path for the document, one listing of notes/ (to find img/) and one of img/.
        assertEquals(1, tree.pathCalls)
        assertEquals(2, tree.listCalls)
        // A miss is cached too: asking again for a missing name costs nothing.
        repeat(10) { assertEquals(ImageStatus.NOT_FOUND, pictures.status("img/gone.png")) }
        assertEquals(2, tree.listCalls)
    }

    @Test
    fun `a new picture turns up once the cache expires or is invalidated`() {
        val listings = TreeListings(tree, nanoTime = { now })
        val folders = PictureFolders(listings, MemoryLinks(), { null }, { listOf(TREE) })
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        val pictures = DocumentPictures(resolver, { doc(docId) }, folders, listings)
        assertEquals(ImageStatus.NOT_FOUND, pictures.status("img/new.png"))
        tree.file(img, "new.png")
        assertEquals(ImageStatus.NOT_FOUND, pictures.status("img/new.png"))
        now += 6_000_000_000L
        assertEquals(ImageStatus.OK, pictures.status("img/new.png"))
        tree.file(img, "newer.png")
        folders.invalidate()
        assertEquals(ImageStatus.OK, pictures.status("img/newer.png"))
    }

    @Test
    fun `a provider that cannot report paths falls back to strict id arithmetic (SEC-9)`() {
        val pathless =
            object : DocumentTree by tree {
                override fun pathTo(
                    tree: String,
                    document: String,
                ): List<String>? = null

                override fun documentId(document: String) = "primary:Notes/doc.md"

                override fun treeId(tree: String) = "primary:Notes"

                override fun uri(
                    tree: String,
                    documentId: String,
                ) = "file://" + File(tmp.root, "x").apply { writeText(documentId) }.absolutePath

                override fun list(
                    tree: String,
                    folder: String,
                ): List<TreeEntry>? = null
            }
        val listings = TreeListings(pathless)
        val folders = PictureFolders(listings, MemoryLinks(), { "content://fake/tree/Notes" }, { emptyList() })
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        val pictures =
            DocumentPictures(resolver, { "content://fake/document/primary%3ANotes%2Fdoc.md" }, folders, listings)
        // primary:Notes/img/a.png is inside the tree; primary:Notes2/… only shares a prefix with it.
        assertEquals("primary:Notes/img/a.png", pictures.bytes("img/a.png")?.decodeToString())
        assertNull(pictures.open("../Notes2/x.png"))
        assertNull(pictures.open("../../x.png"))
    }

    @Test
    fun `id arithmetic keeps to whole path segments of the tree`() {
        assertTrue(PicturePaths.isWithin("primary:Notes/a.png", "primary:Notes"))
        assertTrue(PicturePaths.isWithin("primary:Notes", "primary:Notes"))
        assertFalse(PicturePaths.isWithin("primary:Notes2/a.png", "primary:Notes"))
        assertTrue(PicturePaths.isWithin("primary:Notes2/a.png", "primary:"))
        assertNull(PicturePaths.arithmetic("primary:Notes/doc.md", "primary:Notes", listOf("..", "Notes2", "a.png")))
        assertEquals(
            "primary:Notes/img/a.png",
            PicturePaths.arithmetic("primary:Notes/doc.md", "primary:Notes", listOf("img", "a.png")),
        )
    }

    @Test
    fun `a draft's pictures are in the drafts folder and never climb above it`() {
        val drafts = tmp.newFolder("drafts")
        File(drafts, "images").mkdirs()
        File(drafts, "images/p.png").writeText("p")
        File(drafts.parentFile, "secret.png").writeText("s")
        val listings = TreeListings(tree)
        val pictures =
            DocumentPictures(
                ApplicationProvider.getApplicationContext<Context>().contentResolver,
                documentUri = { "draft:d1" },
                folders = PictureFolders(listings, MemoryLinks(), { null }, { emptyList() }),
                listings = listings,
                draftFile = { if (it == "draft:d1") File(drafts, "d1.md") else null },
            )
        assertEquals("p", pictures.bytes("images/p.png")?.decodeToString())
        assertEquals(ImageStatus.OK, pictures.status("images/p.png"))
        assertNull(pictures.bytes("../secret.png"))
        assertEquals(ImageStatus.NOT_FOUND, pictures.status("images/none.png"))
    }

    @Test
    fun `a picture larger than the cap is refused without reading all of it (SEC-8)`() {
        val big = ByteArray(1_000) { 1 }
        assertArrayEquals(big, BoundedRead.bytes(ByteArrayInputStream(big), 1_000))
        assertNull(BoundedRead.bytes(ByteArrayInputStream(big), 999))
        var served = 0
        val endless =
            object : java.io.InputStream() {
                override fun read(): Int = 1.also { served++ }
            }
        assertNull(BoundedRead.bytes(endless, 10_000))
        assertTrue(served <= 10_001)
    }
}

/** [FolderLinks] in memory. */
class MemoryLinks : FolderLinks {
    val links = HashMap<String, String>()

    override fun get(document: String): String? = links[document]

    override fun set(
        document: String,
        tree: String,
    ) {
        links[document] = tree
    }
}
