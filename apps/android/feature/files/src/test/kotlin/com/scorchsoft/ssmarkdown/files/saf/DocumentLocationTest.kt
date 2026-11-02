package com.scorchsoft.ssmarkdown.files.saf

import com.scorchsoft.ssmarkdown.files.provider.DocumentUri
import com.scorchsoft.ssmarkdown.files.saf.DocumentLocation.Place
import org.junit.Assert.assertEquals
import org.junit.Test

class DocumentLocationTest {
    private fun location(uri: String) = DocumentLocation.of(DocumentUri(uri))

    @Test
    fun `external storage shows the volume and the folders above the file`() {
        val tree =
            location(
                "content://com.android.externalstorage.documents/tree/primary%3ANotes" +
                    "/document/primary%3ANotes%2F2024%2Fplan%20%2B%20ideas.md",
            )
        assertEquals(DocumentLocation(Place.InternalStorage, listOf("Notes", "2024")), tree)
        assertEquals("Internal storage › Notes › 2024", tree.displayString())

        val single = location("content://com.android.externalstorage.documents/document/primary%3Atodo.md")
        assertEquals("Internal storage", single.displayString())

        val card = location("content://com.android.externalstorage.documents/document/1A2B-3C4D%3ABooks%2Fch1.md")
        assertEquals("SD card › Books", card.displayString())
    }

    @Test
    fun `known cloud and system providers are named`() {
        assertEquals(
            Place.GoogleDrive,
            location("content://com.google.android.apps.docs.storage/document/acc%3D1%3Bdoc%3D9").place,
        )
        assertEquals(
            Place.Downloads,
            location("content://com.android.providers.downloads.documents/document/raw%3A%2Fx.md").place,
        )
        assertEquals(
            Place.OneDrive,
            location("content://com.microsoft.skydrive.content.StorageAccessProvider/document/1").place,
        )
        assertEquals("Dropbox", location("content://com.dropbox.android.FileCache/document/x").displayString())
    }

    @Test
    fun `drafts, plain files and unknown apps`() {
        assertEquals("Drafts", location("ssmd-draft:42").displayString())
        assertEquals("This device › sdcard › Notes", location("file:///sdcard/Notes/a.md").displayString())
        assertEquals("org.example.notes", location("content://org.example.notes/doc/7").displayString())
        assertEquals("weird", location("weird:thing").displayString())
    }

    @Test
    fun `long paths keep the last folders and a localised place name`() {
        val deep = DocumentLocation(Place.InternalStorage, listOf("a", "b", "c", "d", "e"))
        assertEquals(
            "Stockage interne › … › c › d › e",
            deep.displayString {
                if (it ==
                    Place.InternalStorage
                ) {
                    "Stockage interne"
                } else {
                    "?"
                }
            },
        )
    }

    @Test
    fun `a malformed escape is kept as typed and emoji survive`() {
        assertEquals(
            DocumentLocation(Place.InternalStorage, listOf("bad%zz", "👋 notes")),
            location("content://com.android.externalstorage.documents/document/primary%3Abad%zz%2F👋%20notes%2Fx.md"),
        )
        assertEquals(
            DocumentLocation(Place.InternalStorage, listOf("100%")),
            location("content://com.android.externalstorage.documents/document/primary%3A100%%2Fx.md"),
        )
    }
}
