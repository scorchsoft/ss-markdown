package com.scorchsoft.ssmarkdown.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** Recent files say where they live, from the URI alone. */
class LocationLabelTest {
    @Test
    fun `external storage shows the folder path`() {
        val uri = "content://com.android.externalstorage.documents/document/primary%3ADocuments%2Fspecs%2Fa.md"
        assertEquals("Documents/specs", LocationLabel.of(uri))
    }

    @Test
    fun `a file at the root of internal storage says so`() {
        assertEquals(
            "Internal storage",
            LocationLabel.of("content://com.android.externalstorage.documents/document/primary%3Aa.md"),
        )
    }

    @Test
    fun `known providers are named, unknown ones left blank`() {
        assertEquals("Drive", LocationLabel.of("content://com.google.android.apps.docs.storage/document/acc%3D1"))
        assertEquals("", LocationLabel.of("content://com.example.unknown/doc/1"))
        assertEquals("", LocationLabel.of("draft:abc"))
    }
}
