package com.scorchsoft.ssmarkdown.media

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.scorchsoft.ssmarkdown.media.FakeDocumentTree.Companion.OTHER_TREE
import com.scorchsoft.ssmarkdown.media.FakeDocumentTree.Companion.ROOT
import com.scorchsoft.ssmarkdown.media.FakeDocumentTree.Companion.TREE
import com.scorchsoft.ssmarkdown.media.FakeDocumentTree.Companion.doc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The document → folder association for a file opened on its own (ADR
 * 0018): a folder is used only if it really holds the document, the choice
 * survives a restart, and grants the app already holds are reused.
 */
@RunWith(RobolectricTestRunner::class)
class PictureFoldersTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private fun prefs() = context.getSharedPreferences("picture_folders_test", Context.MODE_PRIVATE)

    private fun fixture(): Pair<FakeDocumentTree, String> {
        val tree = FakeDocumentTree(tmp.newFolder())
        val notes = tree.folder(ROOT, "notes")
        return tree to tree.file(notes, "doc.md")
    }

    @Test
    fun `a folder that holds the document is remembered across a restart`() {
        val (tree, id) = fixture()
        val first = PictureFolders(TreeListings(tree), PreferencesFolderLinks(::prefs), { null }, { emptyList() })
        assertNull(first.treeFor(doc(id)))
        assertTrue(first.link(doc(id), TREE))
        assertEquals(TREE, first.treeFor(doc(id)))
        // A new process: the link is read back from storage, no grant is asked for again.
        val again = PictureFolders(TreeListings(tree), PreferencesFolderLinks(::prefs), { null }, { emptyList() })
        assertEquals(TREE, again.treeFor(doc(id)))
    }

    @Test
    fun `an unrelated folder is refused and not remembered`() {
        val (tree, id) = fixture()
        val links = MemoryLinks()
        val folders = PictureFolders(TreeListings(tree), links, { null }, { emptyList() })
        assertFalse(folders.link(doc(id), OTHER_TREE))
        assertTrue(links.links.isEmpty())
        assertNull(folders.treeFor(doc(id)))
        // A document from another provider is never "in" this tree either.
        assertFalse(folders.contains(TREE, "content://elsewhere/document/$id"))
    }

    @Test
    fun `a folder grant the app already holds is reused automatically`() {
        val (tree, id) = fixture()
        val held = listOf("content://fake/document/n9", OTHER_TREE, TREE)
        val folders = PictureFolders(TreeListings(tree), MemoryLinks(), { null }, { held })
        assertEquals(TREE, folders.treeFor(doc(id)))
    }

    @Test
    fun `folder mode's tree comes first`() {
        val (tree, id) = fixture()
        val links = MemoryLinks().apply { links[doc(id)] = OTHER_TREE }
        val folders = PictureFolders(TreeListings(tree), links, { TREE }, { emptyList() })
        assertEquals(TREE, folders.treeFor(doc(id)))
    }

    @Test
    fun `the remembered links stay bounded, oldest out first`() {
        var clock = 0L
        val links = PreferencesFolderLinks(::prefs) { clock++ }
        repeat(PreferencesFolderLinks.MAX_LINKS + 5) { links["doc$it"] = TREE }
        assertEquals(PreferencesFolderLinks.MAX_LINKS, prefs().all.size)
        assertNull(links["doc0"])
        assertEquals(TREE, links["doc${PreferencesFolderLinks.MAX_LINKS + 4}"])
    }

    @Test
    fun `a picker starts in the document's folder when its id says where that is`() {
        val doc = "content://com.android.externalstorage.documents/document/primary%3ANotes%2F2024%2Fa.md"
        assertEquals(
            "content://com.android.externalstorage.documents/document/primary%3ANotes%2F2024",
            InitialFolder.of(doc),
        )
        // At the root of a volume, or with an opaque id (Drive): the document itself.
        val top = "content://com.android.externalstorage.documents/document/primary%3Aa.md"
        assertEquals(top, InitialFolder.of(top))
        val drive = "content://com.google.android.apps.docs.storage/document/acc%3D1%3Bdoc%3Dencoded%3Dabc"
        assertEquals(drive, InitialFolder.of(drive))
        assertNull(InitialFolder.of("draft:1234"))
    }
}
