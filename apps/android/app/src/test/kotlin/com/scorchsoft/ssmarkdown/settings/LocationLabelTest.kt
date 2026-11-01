package com.scorchsoft.ssmarkdown.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Recent files say where they live, from the URI alone. */
class LocationLabelTest {
    @Test
    fun `external storage shows the folder path`() {
        val uri = "content://com.android.externalstorage.documents/document/primary%3ADocuments%2Fspecs%2Fa.md"
        val label = LocationLabel.of(uri)
        assertTrue(label, label.contains("Documents") && label.contains("specs"))
    }

    @Test
    fun `providers are named`() {
        assertTrue(
            LocationLabel.of("content://com.google.android.apps.docs.storage/document/acc%3D1").contains("Drive"),
        )
    }

    @Test
    fun `drafts get no label`() {
        assertEquals("", LocationLabel.of("draft:abc"))
    }
}
