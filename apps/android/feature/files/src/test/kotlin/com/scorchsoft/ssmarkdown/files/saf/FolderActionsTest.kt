package com.scorchsoft.ssmarkdown.files.saf

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.scorchsoft.ssmarkdown.files.document.DocumentObserver
import com.scorchsoft.ssmarkdown.files.provider.ContentResolverProvider
import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import com.scorchsoft.ssmarkdown.files.provider.ProviderResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * Folder mode's Android edge, through a real ContentResolver: the drawer's
 * Rename · Duplicate · Delete and New file, persisted grants, and the
 * ContentObserver that turns a provider's change ping into a check.
 */
@RunWith(RobolectricTestRunner::class)
class FolderActionsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val resolver get() = ApplicationProvider.getApplicationContext<Context>().contentResolver
    private val actions by lazy { DocumentActions(resolver, ContentResolverProvider(resolver)) }
    private val folder = DocumentUri(FolderProviderFake.folderUri.toString())

    @Before
    fun setUp() {
        FolderProviderFake.dir = tmp.root
        FolderProviderFake.refuseCreate = false
        Robolectric.setupContentProvider(FolderProviderFake::class.java, FolderProviderFake.AUTHORITY)
    }

    private fun file(name: String) = File(tmp.root, name)

    private fun doc(name: String) = DocumentUri(FolderProviderFake.uriFor(name).toString())

    @Test
    fun `create makes an empty file and returns its uri`() {
        val created = (actions.create(folder, "ideas.md") as ProviderResult.Ok).value
        assertEquals(FolderProviderFake.uriFor("ideas.md").toString(), created.value)
        assertTrue(file("ideas.md").isFile)
        assertEquals(0L, file("ideas.md").length())
        // A clash gets the provider's free name, never an overwrite.
        file("ideas.md").writeText("keep me")
        val second = (actions.create(folder, "ideas.md") as ProviderResult.Ok).value
        assertEquals("ideas (1).md", Uri.parse(second.value).lastPathSegment)
        assertEquals("keep me", file("ideas.md").readText())
    }

    @Test
    fun `a folder that refuses creation is a failure, not a crash`() {
        FolderProviderFake.refuseCreate = true
        assertTrue(actions.create(folder, "x.md") is ProviderResult.Failure)
        assertFalse(file("x.md").exists())
    }

    @Test
    fun `rename moves the file and returns the provider's new uri`() {
        file("draft.md").writeText("text")
        val renamed =
            (
                actions.rename(
                    DocumentUri(FolderProviderFake.uriFor("draft.md").toString()),
                    "final.md",
                ) as ProviderResult.Ok
            ).value
        assertEquals("final.md", Uri.parse(renamed.value).lastPathSegment)
        assertFalse(file("draft.md").exists())
        assertEquals("text", file("final.md").readText())
    }

    @Test
    fun `renaming a file that is gone fails cleanly, even when the provider answers null`() {
        val result = actions.rename(doc("ghost.md"), "x.md")
        assertEquals(ProviderResult.NotFound, result)
    }

    @Test
    fun `duplicate copies the bytes exactly into a new file`() {
        val bytes = "# Notes\r\nwith CRLF and émoji 👋\n".toByteArray()
        file("a.md").writeBytes(bytes)
        val copy =
            (
                actions.duplicate(
                    DocumentUri(FolderProviderFake.uriFor("a.md").toString()),
                    folder,
                    "a copy.md",
                ) as ProviderResult.Ok
            ).value
        assertEquals("a copy.md", Uri.parse(copy.value).lastPathSegment)
        assertTrue(bytes.contentEquals(file("a copy.md").readBytes()))
        assertTrue(bytes.contentEquals(file("a.md").readBytes()))
    }

    @Test
    fun `duplicating a missing file reports it missing`() {
        val result = actions.duplicate(doc("none.md"), folder, "copy.md")
        assertEquals(ProviderResult.NotFound, result)
    }

    @Test
    fun `delete removes the file`() {
        file("old.md").writeText("bye")
        assertTrue(actions.delete(doc("old.md")) is ProviderResult.Ok)
        assertFalse(file("old.md").exists())
    }

    @Test
    fun `persisted grants are taken, listed with their write flag, and released`() {
        val permissions = PersistedPermissions(resolver)
        val tree = DocumentUri("content://${FolderProviderFake.AUTHORITY}/tree/notes")
        val single = doc("read-only.md")

        assertTrue(permissions.take(tree))
        assertTrue(permissions.take(single, write = false))
        assertEquals(mapOf(tree to true, single to false), permissions.held())

        permissions.release(tree)
        assertEquals(mapOf(single to false), permissions.held())
        // Releasing what is not held is harmless.
        permissions.release(tree)
    }

    @Test
    fun `a provider's change ping reaches the hook, until observing stops`() {
        val changed = mutableListOf<DocumentUri>()
        val observer = DocumentObserver(resolver, Handler(Looper.getMainLooper())) { changed += it }
        val uri = doc("watched.md")
        val draft = DocumentUri("${DocumentUri.DRAFT_SCHEME}:1")

        observer.observe(uri)
        observer.observe(uri)
        observer.observe(draft)
        assertEquals(setOf(uri), observer.observed)

        resolver.notifyChange(Uri.parse(uri.value), null)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(uri), changed)

        // Another document's ping is not ours.
        resolver.notifyChange(FolderProviderFake.uriFor("other.md"), null)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(uri), changed)

        observer.stopAll()
        resolver.notifyChange(Uri.parse(uri.value), null)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(uri), changed)
        assertTrue(observer.observed.isEmpty())
    }
}
