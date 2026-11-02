package com.scorchsoft.ssmarkdown.media

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.scorchsoft.ssmarkdown.platform.BoundedRead
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Pictures beside a document: found through the folder grant for every
 * provider (gap d), never outside the granted tree (SEC-9), and never read
 * whole when hostile-sized (SEC-8).
 */
@RunWith(RobolectricTestRunner::class)
class DocumentPicturesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /**
     * An in-memory provider with opaque ids, like Drive: the tree root R holds
     * `notes/` (F) with `doc.md` (D) and `img/` (I, holding `a.png` = A), and
     * `x.png` (X) at the root. Its URIs point at real files so they open.
     */
    private inner class FakeTree(
        private val canSayPath: Boolean = true,
    ) : DocumentTree {
        val files = HashMap<String, File>()
        private val kids =
            mapOf(
                "R" to mapOf("notes" to "F", "x.png" to "X"),
                "F" to mapOf("doc.md" to "D", "img" to "I"),
                "I" to mapOf("a.png" to "A"),
            )
        val lookups = ArrayList<String>()

        override fun pathTo(
            tree: String,
            document: String,
        ) = listOf("R", "F", "D").takeIf { canSayPath && document == DOC }

        override fun child(
            tree: String,
            folder: String,
            name: String,
        ): String? {
            lookups += "$folder/$name"
            return kids[folder]?.get(name)
        }

        override fun uri(
            tree: String,
            documentId: String,
        ): String {
            val file =
                files.getOrPut(documentId) {
                    tmp.newFile(documentId.replace(Regex("[^A-Za-z0-9]"), "_") + ".bin").apply { writeText(documentId) }
                }
            return "file://${file.absolutePath}"
        }

        override fun documentId(document: String) = "primary:Notes/doc.md"

        override fun treeId(tree: String) = "primary:Notes"
    }

    private fun pictures(tree: DocumentTree) =
        DocumentPictures(
            ApplicationProvider.getApplicationContext<Context>().contentResolver,
            documentUri = { DOC },
            treeUri = { TREE },
            tree = tree,
        )

    @Test
    fun `pictures resolve through the tree grant on a provider with opaque ids`() {
        val tree = FakeTree()
        val pictures = pictures(tree)
        assertEquals("A", pictures.bytes("img/a.png")?.decodeToString())
        assertEquals("A", pictures.bytes("./img/../img/a.png")?.decodeToString())
        assertEquals("X", pictures.bytes("../x.png")?.decodeToString())
        assertNull(pictures.bytes("../../x.png"))
        assertNull(pictures.bytes("img/missing.png"))
        assertNull(pictures.bytes("https://example.com/a.png"))
    }

    @Test
    fun `a provider that cannot report paths falls back to strict id arithmetic (SEC-9)`() {
        val pictures = pictures(FakeTree(canSayPath = false))
        // primary:Notes/img/a.png is inside the tree; primary:Notes2/… only shares a prefix with it.
        assertTrue(pictures.open("img/a.png") != null)
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
    fun `a file document's pictures never climb above its folder`() {
        val dir = tmp.newFolder("drafts", "d1")
        File(dir, "p.png").writeText("p")
        File(dir.parentFile, "secret.png").writeText("s")
        val pictures =
            DocumentPictures(
                ApplicationProvider.getApplicationContext<Context>().contentResolver,
                documentUri = { "file://${File(dir, "draft.md").absolutePath}" },
                treeUri = { null },
                tree = FakeTree(),
            )
        assertEquals("p", pictures.bytes("p.png")?.decodeToString())
        assertNull(pictures.bytes("../secret.png"))
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

    private companion object {
        const val TREE = "content://com.example.docs/tree/R"
        const val DOC = "content://com.example.docs/tree/R/document/D"
    }
}
