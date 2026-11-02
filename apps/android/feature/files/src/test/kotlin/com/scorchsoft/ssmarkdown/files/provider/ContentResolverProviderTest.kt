package com.scorchsoft.ssmarkdown.files.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import com.scorchsoft.ssmarkdown.files.saf.IncomingRequest
import com.scorchsoft.ssmarkdown.files.saf.IncomingRequests
import com.scorchsoft.ssmarkdown.files.saf.IntentFields
import com.scorchsoft.ssmarkdown.files.saf.from
import com.scorchsoft.ssmarkdown.files.save.BaseCopyStore
import com.scorchsoft.ssmarkdown.files.save.SaveOutcome
import com.scorchsoft.ssmarkdown.files.save.SavePipeline
import com.scorchsoft.ssmarkdown.files.save.SaveRequest
import com.scorchsoft.ssmarkdown.history.journal.BaseFingerprint
import com.scorchsoft.ssmarkdown.text.PieceTree
import com.scorchsoft.ssmarkdown.text.TextFileFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.FileNotFoundException

/** The Android edge: a real ContentResolver talking to a real ContentProvider. */
@RunWith(RobolectricTestRunner::class)
class ContentResolverProviderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val resolver get() = ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver
    private val uri = DocumentUri("content://$AUTHORITY/note.md")

    @Before
    fun setUp() {
        TestProvider.dir = tmp.root
        TestProvider.denyWrites = false
        Robolectric.setupContentProvider(TestProvider::class.java, AUTHORITY)
        File(tmp.root, "note.md").writeText("old text that is long\n")
    }

    @Test
    fun `metadata, read and a verified save go through the resolver`() {
        val provider = ContentResolverProvider(resolver)
        val metadata = (provider.metadata(uri) as ProviderResult.Ok).value
        assertEquals("note.md", metadata.displayName)
        assertEquals(22L, metadata.size)

        val bytes = File(tmp.root, "note.md").readBytes()
        val base = BaseFingerprint.of(bytes, metadata.lastModified)
        val request = SaveRequest(uri, PieceTree.of("new\n").snapshot(), TextFileFormat.DEFAULT, base)
        val outcome = SavePipeline(provider, BaseCopyStore(tmp.newFolder("base"))).save(request)

        assertTrue(outcome.toString(), outcome is SaveOutcome.Saved)
        assertEquals("new\n", File(tmp.root, "note.md").readText())
    }

    @Test
    fun `a missing document and a refused write are told apart`() {
        val provider = ContentResolverProvider(resolver)
        assertEquals(ProviderResult.NotFound, provider.openInput(DocumentUri("content://$AUTHORITY/none.md")))

        TestProvider.denyWrites = true
        assertEquals(ProviderResult.PermissionDenied, provider.openOutput(uri, WriteMode.Truncate))
    }

    @Test
    fun `intent fields are read from a real intent`() {
        val intent =
            Intent(Intent.ACTION_VIEW, Uri.parse(uri.value))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

        assertEquals(
            IncomingRequest.OpenInPlace(uri, writable = true),
            IncomingRequests.parse(IntentFields.from(intent)),
        )
    }

    /** Serves files from [dir]; just enough of a documents provider. */
    class TestProvider : ContentProvider() {
        override fun onCreate() = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            val file = fileOf(uri)
            val columns =
                arrayOf(
                    OpenableColumns.DISPLAY_NAME,
                    OpenableColumns.SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                )
            return MatrixCursor(
                columns,
            ).apply { if (file.exists()) addRow(arrayOf(file.name, file.length(), file.lastModified())) }
        }

        override fun openFile(
            uri: Uri,
            mode: String,
        ): ParcelFileDescriptor {
            if (denyWrites && 'w' in mode) throw SecurityException("read-only grant")
            val file = fileOf(uri)
            if (!file.exists()) throw FileNotFoundException(uri.toString())
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
        }

        override fun getType(uri: Uri) = "text/markdown"

        override fun insert(
            uri: Uri,
            values: ContentValues?,
        ): Uri? = null

        override fun delete(
            uri: Uri,
            selection: String?,
            selectionArgs: Array<out String>?,
        ) = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ) = 0

        private fun fileOf(uri: Uri) = File(dir, uri.lastPathSegment.orEmpty())

        companion object {
            lateinit var dir: File
            var denyWrites = false
        }
    }

    private companion object {
        const val AUTHORITY = "com.scorchsoft.ssmarkdown.test.docs"
    }
}
